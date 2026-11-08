package com.hyperos.xj.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

// androidApp 入口无法注册 Application，经 ActivityThread 反射取 Application Context（与 AppearanceStore 同法）
private val toastContext: Context? by lazy {
    runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}

actual fun showToast(message: String) {
    val context = toastContext ?: return
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    } else {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
