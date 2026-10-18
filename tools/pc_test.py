# -*- coding: utf-8 -*-
"""秒答 PC 端测试脚本：验证「题目截图 -> 视觉模型 -> 只吐答案」链路的准确率。

用法：
  1) 先设置环境变量（Git Bash 示例）：
     export QUICKDOT_BASE_URL="https://你的接口地址/v4"
     export QUICKDOT_API_KEY="你的key"
     export QUICKDOT_MODEL="模型名"
  2) 测一张题图：      python pc_test.py 题目.png
  3) 同一题多屏拼接：  python pc_test.py 上半屏.png 下半屏.png
  4) 整屏截图并识别：  python pc_test.py --screen   （需要 pip install pillow）
  5) 批量测一个文件夹：python pc_test.py 截图文件夹
     （文件名里若写了标准答案，如 "第3题(A).png"，会自动对答案统计正确率）
"""
import base64
import json
import os
import re
import sys
import urllib.request

BASE = os.environ.get("QUICKDOT_BASE_URL", "").rstrip("/")
KEY = os.environ.get("QUICKDOT_API_KEY", "")
MODEL = os.environ.get("QUICKDOT_MODEL", "")
MAX_TOKENS = int(os.environ.get("QUICKDOT_MAX_TOKENS", "2000"))

SYSTEM_PROMPT = """你是答题器。用户发来一张手机答题界面的截图，屏幕上有一道正在作答的题目（题干+选项），画面里的倒计时、进度条、答题卡按钮、水印、导航栏都是无关元素，全部忽略。
只处理三种题型：单选题、多选题、判断题。如果屏幕上有多道题，选最完整、最像"当前正在作答"的那一道。
按下面三步工作：
第一步：仔细读出题干和所有可见选项。如果有选项被屏幕边缘截断，基于可见部分推断，并在解析里如实说明"某选项被截断"。
第二步：逐个分析选项，排除明显错误的，选出最可能的答案。
第三步：输出。先写解析，最后一行写答案行，格式严格遵守：
- 解析：一般不超过150字；数学等复杂题可分步推理，最长不超过400字，说明为什么选它、其余选项为什么不对。
- 答案行（必须是最后一行，以"答案："开头）：
  单选题：答案：A
  多选题：答案：ABD（按字母顺序，不要有空格或顿号）
  判断题：答案：对 或 答案：错（即使选项写的是"A对/B错"，也要输出 对 或 错，不要输出字母）
  无法确定答案、不是这三种题型、或截图里没有题目：答案：?
除解析和答案行外，不要输出任何其他内容。"""


def chat_url():
    return BASE if BASE.endswith("/chat/completions") else BASE + "/chat/completions"


def ask(image_paths):
    """image_paths：一或多张截图（多张=同一道题的连续画面，按顺序拼接）"""
    content = []
    if len(image_paths) > 1:
        content.append({"type": "text", "text": f"以上{len(image_paths)}张截图是同一道题的连续画面（按顺序）。请综合所有截图完整阅读题干和选项后作答。"})
    else:
        content.append({"type": "text", "text": "请作答"})
    for image_path in image_paths:
        ext = os.path.splitext(image_path)[1].lower().lstrip(".")
        if ext == "jpg":
            ext = "jpeg"
        with open(image_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        content.append({"type": "image_url", "image_url": {"url": f"data:image/{ext};base64,{b64}"}})
    payload = {
        "model": MODEL,
        "temperature": 0.2,
        "max_tokens": MAX_TOKENS,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": content},
        ],
    }
    req = urllib.request.Request(
        chat_url(),
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    content_out = data["choices"][0]["message"]["content"]
    return parse_answer(content_out)


def parse_answer(content):
    """与 App 端 ApiClient.parseAnswer 保持一致：优先取最后一行「答案：X」。"""
    lines = [l.strip() for l in content.strip().splitlines() if l.strip()]
    ans_line = None
    for l in reversed(lines):
        if l.startswith("答案：") or l.startswith("答案:"):
            ans_line = l
            break
    if ans_line:
        first = ans_line.replace("答案：", "", 1).replace("答案:", "", 1).strip()
        analysis = "\n".join(l for l in lines if l != ans_line).strip()
    else:
        first = lines[0] if lines else "?"
        analysis = "\n".join(lines[1:]).strip()
    return first, analysis


def normalize(ans):
    a = ans.strip().upper()
    if a in ("对", "正确", "√", "T", "TRUE"):
        return "对"
    if a in ("错", "错误", "×", "X", "F", "FALSE"):
        return "错"
    letters = "".join(sorted(set(re.findall(r"[A-H]", a))))
    return letters if letters else "?"


def expected_from_name(name):
    m = re.search(r"\(([A-Ha-h对错√×]+)\)", name)
    return m.group(1) if m else None


def grab_screen(path):
    from PIL import ImageGrab
    ImageGrab.grab().save(path)


def main():
    if not (BASE and KEY and MODEL):
        print("请先设置环境变量 QUICKDOT_BASE_URL / QUICKDOT_API_KEY / QUICKDOT_MODEL")
        sys.exit(1)
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    target = args[0]
    if target == "--screen":
        import tempfile
        p = os.path.join(tempfile.gettempdir(), "quickdot_screen.png")
        grab_screen(p)
        a, s = ask([p])
        print("答案:", a)
        print("解析:", s)
    elif os.path.isdir(target):
        total = correct = 0
        for name in sorted(os.listdir(target)):
            if not name.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
                continue
            path = os.path.join(target, name)
            try:
                a, _ = ask([path])
                print(f"[{name}] -> {a}")
            except Exception as e:
                print(f"[{name}] -> 请求失败: {e}")
                continue
            exp = expected_from_name(name)
            if exp:
                total += 1
                if normalize(a) == normalize(exp):
                    correct += 1
                else:
                    print(f"    ✗ 应为 {exp}，实答 {a}")
        if total:
            print(f"\n带标准答案的题：{correct}/{total} 正确（{correct * 100 // total}%）")
    elif os.path.isfile(target):
        # 支持多张图：python pc_test.py 上半屏.png 下半屏.png （同一题拼接）
        a, s = ask(args)
        print("答案:", a)
        print("解析:", s)
    else:
        print("路径不存在:", target)


if __name__ == "__main__":
    main()
