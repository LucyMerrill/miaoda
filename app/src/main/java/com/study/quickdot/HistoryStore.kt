package com.study.quickdot

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜题历史：SharedPreferences 存纯文本记录（不存图片），最新在前，容量上限先进先出。
 * 判重用识屏截图的 dHash 感知哈希——倒计时、进度条等小区域变化不会让哈希偏离太多。
 */
object HistoryStore {

    private const val PREFS = "quickdot_history"
    private const val KEY = "records"

    const val CAPACITY = 100
    const val MATCH_DISTANCE = 12   // 64bit 海明距离 ≤ 此值视为同一道题

    data class Record(
        val time: Long,
        val question: String,   // 题干转录首行（截 80 字），作列表摘要
        val answer: String,
        val analysis: String,
        val model: String,
        val hash: Long
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

    /** 在历史里找与哈希最近的题；记录按新→旧排列，返回第一条命中的（即最近一次） */
    fun findByHash(records: List<Record>, hash: Long): Record? =
        records.firstOrNull { java.lang.Long.bitCount(it.hash xor hash) <= MATCH_DISTANCE }

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
        .put("h", r.hash)

    private fun fromJson(o: JSONObject): Record = Record(
        time = o.optLong("t"),
        question = o.optString("q"),
        answer = o.optString("a"),
        analysis = o.optString("x"),
        model = o.optString("m"),
        hash = o.optLong("h")
    )

    // ---------- 感知哈希 ----------

    /** dHash：缩到 9×8 灰度，每行相邻像素比较取 64bit；对局部小变化（倒计时等）不敏感 */
    fun dHash(bmp: Bitmap): Long {
        val w = 9
        val h = 8
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        var hash = 0L
        var bit = 63
        for (y in 0 until h) {
            for (x in 0 until w - 1) {
                if (gray(small.getPixel(x, y)) > gray(small.getPixel(x + 1, y))) {
                    hash = hash or (1L shl bit)
                }
                bit--
            }
        }
        return hash
    }

    private fun gray(p: Int): Int = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
}
