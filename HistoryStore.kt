package com.study.quickdot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜题历史：SharedPreferences 存纯文本记录（时间/题干摘要/答案/解析/模型名），
 * 最新在前，容量上限先进先出。仅作留档回看，不参与识屏流程。
 */
object HistoryStore {

    private const val PREFS = "quickdot_history"
    private const val KEY = "records"

    const val CAPACITY = 100

    data class Record(
        val time: Long,
        val question: String,   // 题干转录首行（截 80 字），作列表摘要
        val answer: String,
        val analysis: String,
        val model: String
    )

    fun add(ctx: Context, r: Record) {
        val arr = loadArray(ctx)
        val all = JSONArray()
        all.put(toJson(r))
        for (i in 0 until arr.length()) all.put(arr.getJSONObject(i))   // 新的插最前
        while (all.length() > CAPACITY) all.remove(all.length() - 1)
        prefs(ctx).edit().putString(KEY, all.toString()).apply()
    }

    fun all(ctx: Context): MutableList<Record> {
        val arr = loadArray(ctx)
        val out = mutableListOf<Record>()
        for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        return out
    }

    fun removeAt(ctx: Context, index: Int) {
        val arr = loadArray(ctx)
        if (index in 0 until arr.length()) arr.remove(index)
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    // ---------- 序列化 ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadArray(ctx: Context): JSONArray =
        try {
            JSONArray(prefs(ctx).getString(KEY, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }

    private fun toJson(r: Record): JSONObject = JSONObject()
        .put("t", r.time)
        .put("q", r.question)
        .put("a", r.answer)
        .put("x", r.analysis)
        .put("m", r.model)

    private fun fromJson(o: JSONObject): Record = Record(
        time = o.optLong("t"),
        question = o.optString("q"),
        answer = o.optString("a"),
        analysis = o.optString("x"),
        model = o.optString("m")
    )
}
