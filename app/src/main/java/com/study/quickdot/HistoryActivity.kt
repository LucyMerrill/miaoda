package com.study.quickdot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 搜题历史列表：最新在前。点击条目展开完整解析，
 * 展开态可复制答案 / 复制解析 / 删除该条；顶部可清空全部。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(24), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = "搜题历史（最新在前，保留最近 ${HistoryStore.CAPACITY} 条）"
            textSize = 16f
            setTextColor(0xFF222222.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClear = Button(this).apply { text = "清空全部" }
        btnClear.setOnClickListener { confirmClear() }
        header.addView(title)
        header.addView(btnClear)
        root.addView(header)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(list) }
        root.addView(
            sv,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val records = HistoryStore.all(this)
        if (records.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "暂无记录。识屏作答后会自动留档，点条目查看完整解析。"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                setPadding(0, dp(24), 0, 0)
            })
            return
        }
        records.forEachIndexed { i, r -> list.addView(itemView(i, r)) }
    }

    private fun itemView(index: Int, r: HistoryStore.Record): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ans = TextView(this).apply {
            text = if (r.answer.length <= 10) "【${r.answer}】" else "【答案】"
            textSize = 15f
            setTextColor(0xFF1E88E5.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        val meta = TextView(this).apply {
            text = "${fmtTime(r.time)} · ${r.question}"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12
            }
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(ans)
        row.addView(meta)
        card.addView(row)

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val body = TextView(this).apply {
            text = r.analysis.ifEmpty { "(无解析)" }
            textSize = 14f
            setTextColor(0xFF222222.toInt())
            setTextIsSelectable(true)
        }
        detail.addView(body)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val btnCopyAns = TextView(this).apply {
            text = "复制答案"
            setTextColor(0xFF43A047.toInt())
            textSize = 14f
            setPadding(0, dp(8), dp(20), dp(8))
        }
        btnCopyAns.setOnClickListener { copy("答案", r.answer) }
        val btnCopyAll = TextView(this).apply {
            text = "复制解析"
            setTextColor(0xFF43A047.toInt())
            textSize = 14f
            setPadding(0, dp(8), dp(20), dp(8))
        }
        btnCopyAll.setOnClickListener { copy("解析", "${r.question}\n${r.analysis}\n答案：${r.answer}") }
        val btnDel = TextView(this).apply {
            text = "删除"
            setTextColor(0xFFD32F2F.toInt())
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        btnDel.setOnClickListener {
            HistoryStore.removeAt(this@HistoryActivity, index)
            toast("已删除该条")
            render()
        }
        btnRow.addView(btnCopyAns)
        btnRow.addView(btnCopyAll)
        btnRow.addView(btnDel)
        detail.addView(btnRow)
        card.addView(detail)

        // 点条目展开/收起解析
        row.setOnClickListener {
            detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        return card
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空历史")
            .setMessage("删除全部搜题记录？")
            .setPositiveButton("清空") { _, _ ->
                HistoryStore.clear(this)
                toast("已清空")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("${label}已复制")
    }

    private fun fmtTime(t: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(t))

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
