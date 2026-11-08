package com.hyperos.xj.runtime

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object AndroidBackgroundPicker {
    private var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var pending: (ByteArray?) -> Unit = {}

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            val callback = pending
            pending = {}
            callback(uri?.let { readBytes(activity, it) })
        }
    }

    fun launch(onResult: (ByteArray?) -> Unit): Boolean {
        val launcher = launcher ?: return false
        pending = onResult
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
        return true
    }

    private fun readBytes(context: Context, uri: android.net.Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}

actual suspend fun pickBackgroundImageBytes(): ByteArray? =
    suspendCancellableCoroutine { continuation ->
        val launched = AndroidBackgroundPicker.launch { bytes ->
            if (continuation.isActive) continuation.resume(bytes)
        }
        if (!launched && continuation.isActive) continuation.resume(null)
    }

actual fun decodeBackgroundImage(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
