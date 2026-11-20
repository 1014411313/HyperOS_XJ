package com.hyperos.xj.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun appVisible(): Boolean {
    val visible = AppVisibility.visible.value
    LaunchedEffect(visible) {
        val lease = File("/storage/emulated/0/Android/data/com.hyperos.xj/files/visible.until")
        try {
            while (visible) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        lease.parentFile?.mkdirs()
                        lease.writeText((System.currentTimeMillis() / 1000 + 15).toString())
                    }
                }
                delay(5_000L)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { lease.writeText("0") } }
        }
    }
    return visible
}
