package com.study.quickdot

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {

    const val SYSTEM_PROMPT = """你是答题器。用户发来一张手机答题界面的截图，屏幕上有一道正在作答的题目（题干+选项），画面里的倒计时、进度条、答题卡按钮、水印、导航栏都是无关元素，全部忽略。
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun chatUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    /** 答题主请求体：系统提示词 + 一或多张屏幕截图 + 按档位给定的输出 token 上限 */
    fun answerPayload(model: String, images: List<String>, maxTokens: Long): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        val content = JSONArray()
        val text = if (images.size > 1)
            "以上${images.size}张截图是同一道题的连续画面（按顺序）。请综合所有截图完整阅读题干和选项后作答。"
        else
            "请作答"
        content.put(JSONObject().put("type", "text").put("text", text))
        for (b64 in images) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", content))
        return JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("messages", messages)
            .toString()
    }

    /** 连接测试请求体：带一张 1x1 图片，兼容只吃图的视觉模型 */
    fun testPayload(model: String): String {
        val tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", "这是一张测试图。只回复两个字：正常"))
        content.put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/png;base64,$tinyPng"))
        )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        return JSONObject().put("model", model).put("max_tokens", 16).put("messages", messages).toString()
    }

    /**
     * 解析模型返回：优先取最后一行「答案：X」格式的答案行（先思考后作答格式），
     * 找不到再退回旧行为（第一行是答案，其余是解析）。
     * 答案归一化：单选/多选统一大写按字母排序；判断归一成 对/错；其余一律 "?"。
     */
    fun parseAnswer(content: String): Pair<String, String> {
        val lines = content.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
        val ansLine = lines.lastOrNull { it.startsWith("答案：") || it.startsWith("答案:") }
        val first: String
        val analysis: String
        if (ansLine != null) {
            first = ansLine.removePrefix("答案：").removePrefix("答案:").trim()
            analysis = lines.filterNot { it == ansLine }.joinToString("\n").trim()
        } else {
            first = lines.firstOrNull() ?: "?"
            analysis = lines.drop(1).joinToString("\n").trim()
        }
        val answer = when {
            first == "?" -> "?"
            first in setOf("对", "正确", "√", "T", "t", "TRUE", "True", "true") -> "对"
            first in setOf("错", "错误", "×", "x", "X", "F", "f", "FALSE", "False", "false") -> "错"
            Regex("^[A-Ha-h]{1,6}$").matches(first) -> first.uppercase().toSortedSet().joinToString("")
            else -> "?"
        }
        return answer to analysis
    }

    fun request(url: String, key: String, jsonBody: String, cb: Callback) {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).enqueue(cb)
    }

    fun test(base: String, key: String, model: String, done: (Boolean, String) -> Unit) {
        request(chatUrl(base), key, testPayload(model), object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                done(false, e.message ?: "网络错误")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.body?.string()
                } catch (e: Exception) {
                    null
                }
                if (!response.isSuccessful || body == null) {
                    done(false, "HTTP ${response.code} ${body?.take(120) ?: ""}")
                    return
                }
                try {
                    val content = JSONObject(body)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                    done(true, content.trim().take(20))
                } catch (e: Exception) {
                    done(false, "返回格式异常：${body.take(120)}")
                }
            }
        })
    }
}
