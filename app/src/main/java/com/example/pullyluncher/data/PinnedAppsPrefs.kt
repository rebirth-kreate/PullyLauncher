package com.example.pullyluncher.data

import android.content.Context
import com.example.pullyluncher.model.PinnedApp
import org.json.JSONArray
import org.json.JSONObject

/** 固定アプリ一覧を SharedPreferences に保存・読み出しするヘルパー。 */
object PinnedAppsPrefs {

    private const val PREFS_NAME = "pully_prefs"
    private const val KEY_PINNED = "pinned_apps"

    fun load(context: Context): List<PinnedApp> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_PINNED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj   = arr.optJSONObject(i) ?: return@mapNotNull null
                val pkg   = obj.optString("pkg").takeIf   { it.isNotBlank() } ?: return@mapNotNull null
                val label = obj.optString("label").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PinnedApp(packageName = pkg, label = label)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, apps: List<PinnedApp>) {
        val arr = JSONArray()
        apps.forEach { app ->
            arr.put(JSONObject().apply {
                put("pkg",   app.packageName)
                put("label", app.label)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PINNED, arr.toString())
            .apply()
    }
}
