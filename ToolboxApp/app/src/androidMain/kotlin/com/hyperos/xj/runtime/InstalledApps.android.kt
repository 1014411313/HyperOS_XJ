package com.hyperos.xj.runtime

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun installedGameCandidates(): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        val context = currentApplication() ?: return@withContext emptyList()
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = pm.queryIntentActivities(launcher, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.applicationInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            pkg to info.loadLabel(pm).toString().ifBlank { pkg }
        }
        val installed = pm.getInstalledApplications(0).mapNotNull { info ->
            val pkg = info.packageName
            if (pkg == context.packageName) return@mapNotNull null
            val label = info.loadLabel(pm).toString()
            pkg to label.ifBlank { pkg }
        }
        (launchable + installed)
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

private fun currentApplication(): Context? = try {
    Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication")
        .invoke(null) as? Context
} catch (_: Throwable) {
    null
}
