package com.study.quickdot

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置存储：多个接口档案（URL + Key + 模型列表）+ 当前选择 + 答题强度。
 * 整体序列化为一个 JSON 字符串存在 SharedPreferences 里。
 */
object ConfigStore {

    const val KEY_CONFIG = "config"

    class Profile(var baseUrl: String, var apiKey: String, val models: MutableList<String>)

    class Config(
        val profiles: MutableList<Profile>,
        var profileIndex: Int,
        var model: String,
        var tokenMode: String,   // low | high | max | custom
        var customTokens: Long
    )

    /** 各档位的输出 token 上限 */
    fun tokenLimit(cfg: Config): Long = when (cfg.tokenMode) {
        "low" -> 512L
        "high" -> 1024L
        "max" -> 3000L
        else -> cfg.customTokens
    }

    fun current(cfg: Config): Profile =
        cfg.profiles[cfg.profileIndex.coerceIn(0, cfg.profiles.size - 1)]

    fun load(prefs: SharedPreferences): Config {
        val raw = prefs.getString(KEY_CONFIG, null)
        if (raw != null) {
            try {
                return parse(JSONObject(raw))
            } catch (e: Exception) {
                // 解析失败则落入下面的旧版迁移
            }
        }
        // 旧版单接口配置迁移
        val baseUrl = prefs.getString("baseUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val model = prefs.getString("model", "") ?: ""
        val models = if (model.isBlank()) mutableListOf() else mutableListOf(model)
        return Config(mutableListOf(Profile(baseUrl, apiKey, models)), 0, model, "high", 2000L)
    }

    fun save(prefs: SharedPreferences, cfg: Config) {
        val arr = JSONArray()
        for (p in cfg.profiles) {
            val o = JSONObject()
            o.put("baseUrl", p.baseUrl)
            o.put("apiKey", p.apiKey)
            o.put("models", JSONArray(p.models))
            arr.put(o)
        }
        val root = JSONObject()
        root.put("profiles", arr)
        root.put("profileIndex", cfg.profileIndex)
        root.put("model", cfg.model)
        root.put("tokenMode", cfg.tokenMode)
        root.put("customTokens", cfg.customTokens)
        prefs.edit().putString(KEY_CONFIG, root.toString()).apply()
    }

    private fun parse(root: JSONObject): Config {
        val arr = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = mutableListOf<Profile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val models = mutableListOf<String>()
            val ma = o.optJSONArray("models") ?: JSONArray()
            for (j in 0 until ma.length()) models.add(ma.getString(j))
            profiles.add(Profile(o.optString("baseUrl"), o.optString("apiKey"), models))
        }
        if (profiles.isEmpty()) throw IllegalStateException("空档案")
        return Config(
            profiles,
            root.optInt("profileIndex", 0).coerceIn(0, profiles.size - 1),
            root.optString("model"),
            root.optString("tokenMode", "high"),
            root.optLong("customTokens", 2000L).coerceIn(64L, 32000L)
        )
    }
}
