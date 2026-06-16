package com.rebirthkreate.pullylauncher.data

import android.content.Context
import android.content.Intent
import com.rebirthkreate.pullylauncher.model.AppEntry

object AppRepository {
    @Suppress("DEPRECATION")
    fun getInstalledLauncherApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .mapNotNull { info ->
                try {
                    AppEntry(
                        packageName = info.activityInfo.packageName,
                        label       = info.loadLabel(pm).toString(),
                        icon        = info.loadIcon(pm)
                    )
                } catch (_: Exception) { null }
            }
            .sortedBy { it.label.lowercase() }
    }
}
