package com.hyperos.xj.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri

// androidApp 入口不可改（无法注册 Application），与 AppearanceStore 同法：经 ActivityThread 反射取 Application Context
private val appContext: Context? by lazy {
    runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}

actual fun openUrl(url: String) {
    val context = appContext ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
