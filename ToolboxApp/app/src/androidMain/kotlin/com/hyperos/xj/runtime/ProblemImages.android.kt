package com.hyperos.xj.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object AndroidProblemImages {
    private const val DIR_NAME = "diag_pics"
    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 82
    private const val MAX_ITEMS = 4

    private var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var pending: (List<Uri>) -> Unit = {}

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_ITEMS)
        ) { uris ->
            val callback = pending
            pending = {}
            callback(uris)
        }
    }

    fun launch(onResult: (List<Uri>) -> Unit): Boolean {
        val launcher = launcher ?: return false
        pending = onResult
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        return true
    }

    fun save(ctx: Context, uris: List<Uri>): List<String> {
        val out = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val saved = ArrayList<String>()
        uris.take(MAX_ITEMS).forEachIndexed { i, uri ->
            val file = File(out, "img_${System.currentTimeMillis()}_$i.jpg")
            if (compress(ctx, uri, file)) saved.add(file.absolutePath) else file.delete()
        }
        return saved
    }

    private fun compress(ctx: Context, uri: Uri, out: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return false
        val longEdge = maxOf(bmp.width, bmp.height)
        val scaled = if (longEdge > MAX_EDGE) {
            val k = MAX_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * k).toInt().coerceAtLeast(1),
                (bmp.height * k).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bmp
        }
        val ok = runCatching {
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }.getOrDefault(false)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return ok
    }
}

actual suspend fun pickProblemImages(maxCount: Int): List<String> {
    val ctx = AndroidBackgroundPicker.appContextOrNull() ?: return emptyList()
    val uris = suspendCancellableCoroutine { cont ->
        val launched = AndroidProblemImages.launch { list -> if (cont.isActive) cont.resume(list) }
        if (!launched && cont.isActive) cont.resume(emptyList())
    }
    if (uris.isEmpty()) return emptyList()
    return withContext(Dispatchers.IO) {
        AndroidProblemImages.save(ctx, uris.take(maxCount.coerceAtLeast(1)))
    }
}

actual fun problemImagesDir(): String {
    val ctx = AndroidBackgroundPicker.appContextOrNull() ?: return ""
    return File(ctx.filesDir, "diag_pics").absolutePath
}

actual fun clearProblemImages(): Int {
    val ctx = AndroidBackgroundPicker.appContextOrNull() ?: return 0
    val files = File(ctx.filesDir, "diag_pics").listFiles() ?: return 0
    var n = 0
    files.forEach { if (it.isFile && it.delete()) n++ }
    return n
}
