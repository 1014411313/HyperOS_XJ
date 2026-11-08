package com.hyperos.xj.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

object AndroidBackgroundPicker {
    private var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var pending: (ByteArray?) -> Unit = {}
    private var appContext: Context? = null

    fun register(activity: ComponentActivity) {
        appContext = activity.applicationContext
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

    /** 背景图的 App 侧副本：/data/local/tmp App uid 读不了，预览从这份直读。 */
    fun rearBgCopyFile(): File? =
        appContext?.getExternalFilesDir(null)?.let { File(it, "xj_rear_bg.png") }

    fun rearBgGifCopyFile(): File? =
        appContext?.getExternalFilesDir(null)?.let { File(it, "xj_rear_bg.gif") }

    /** 内置默认动图的 App 副本；与用户自选图分文件，恢复默认时读这份。 */
    fun rearBgDefaultGifCopyFile(): File? =
        appContext?.getExternalFilesDir(null)?.let { File(it, "xj_rear_bg_default.gif") }

    /** App 侧 GIF 副本缺失时经 su 从引擎侧补铺：service.sh 开机铺设可能因 FUSE 未就绪静默失败（bug-059）。 */
    fun restoreEngineGifCopy(target: File, enginePath: String = "/data/local/tmp/xj_rear_bg.gif"): Boolean {
        val context = appContext ?: return false
        val probe = ProcessBuilder("su", "-c", "[ -f $enginePath ]")
            .redirectErrorStream(true).start()
        if (probe.waitFor() != 0) return false
        val uid = context.applicationInfo.uid
        val targetPath = "'${target.absolutePath}'"
        val process = ProcessBuilder(
            "su", "-c",
            "cp -f $enginePath $targetPath && chmod 0644 $targetPath && chown $uid:$uid $targetPath"
        ).redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        if (process.waitFor() != 0) {
            runCatching { target.delete() }
            return false
        }
        return true
    }

    private fun readBytes(context: Context, uri: android.net.Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}

/** su 写入 /data/local/tmp 下指定文件并 chmod 0644：该目录 root 可写、系统进程（subscreencenter）可读。 */
private fun writeViaSu(data: ByteArray, name: String): Boolean {
    val process = ProcessBuilder(
        "su", "-c",
        "cat > /data/local/tmp/$name && chmod 0644 /data/local/tmp/$name"
    ).redirectErrorStream(true).start()
    process.outputStream.use { it.write(data) }
    process.inputStream.use { it.readBytes() }
    return process.waitFor() == 0
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

/**
 * GIF 按原格式经 su 写入 /data/local/tmp/xj_rear_bg.gif，非 GIF 转码 PNG 写 xj_rear_bg.png；
 * App 外部副本只保留当前类型那份（互斥清理），返回引擎侧路径。
 */
actual suspend fun writeRearBackgroundImage(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
    runCatching {
        if (isGifBytes(bytes)) {
            val name = "xj_rear_bg.gif"
            if (!writeViaSu(bytes, name)) return@runCatching null
            AndroidBackgroundPicker.rearBgCopyFile()?.let { file ->
                runCatching { file.delete() }
            }
            val gifCopy = AndroidBackgroundPicker.rearBgGifCopyFile()
            if (gifCopy != null) {
                GifFrameCache.evict(gifCopy.absolutePath)
                gifCopy.writeBytes(bytes)
            }
            "/data/local/tmp/$name"
        } else {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            val png = ByteArrayOutputStream().also { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()
            bitmap.recycle()
            if (!writeViaSu(png, "xj_rear_bg.png")) return@runCatching null
            AndroidBackgroundPicker.rearBgGifCopyFile()?.let { file ->
                runCatching { file.delete() }
            }
            AndroidBackgroundPicker.rearBgCopyFile()?.writeBytes(png)
            "/data/local/tmp/xj_rear_bg.png"
        }
    }.getOrNull()
}

actual suspend fun rearDefaultBgGifPreviewPath(restoreFromEngine: Boolean): String? = withContext(Dispatchers.IO) {
    runCatching {
        val file = AndroidBackgroundPicker.rearBgDefaultGifCopyFile() ?: return@runCatching null
        if (!file.isFile && restoreFromEngine &&
            !AndroidBackgroundPicker.restoreEngineGifCopy(file, "/data/local/tmp/xj_rear_bg_default.gif")
        ) {
            return@runCatching null
        }
        if (isGifFile(file)) file.absolutePath else null
    }.getOrNull()
}

actual suspend fun readRearCardBgImageBytes(): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        AndroidBackgroundPicker.rearBgCopyFile()?.takeIf { it.isFile }?.readBytes()
    }.getOrNull()
}

actual suspend fun rearBgGifPreviewPath(restoreFromEngine: Boolean): String? = withContext(Dispatchers.IO) {
    runCatching {
        val file = AndroidBackgroundPicker.rearBgGifCopyFile() ?: return@runCatching null
        if (!file.isFile && restoreFromEngine && !AndroidBackgroundPicker.restoreEngineGifCopy(file)) {
            return@runCatching null
        }
        if (isGifFile(file)) file.absolutePath else null
    }.getOrNull()
}

/** 预览取帧上限：超出时等比缩小缓冲，避免超大 GIF 占满内存。 */
private const val GIF_PREVIEW_MAX_SIDE = 1600f

/** Movie 与帧缓冲按路径缓存：预览按时间取帧时避免重复解码与反复分配位图。 */
private object GifFrameCache {
    val movies = HashMap<String, Movie>()
    val buffers = HashMap<String, Bitmap>()
    val canvases = HashMap<String, Canvas>()
    val shrinks = HashMap<String, Float>()
    val failed = HashSet<String>()

    fun evict(path: String) {
        movies.remove(path)
        buffers.remove(path)
        canvases.remove(path)
        shrinks.remove(path)
        failed.remove(path)
    }
}

actual fun gifFrameAt(path: String, timeMs: Long): ImageBitmap? {
    if (path in GifFrameCache.failed) return null
    val movie = GifFrameCache.movies[path] ?: run {
        val decoded = try {
            Movie.decodeFile(path)
        } catch (_: Throwable) {
            null
        }
        if (decoded == null) {
            GifFrameCache.failed.add(path)
            return null
        }
        GifFrameCache.movies[path] = decoded
        decoded
    }
    val width = movie.width()
    val height = movie.height()
    if (width <= 0 || height <= 0) return null
    var buffer = GifFrameCache.buffers[path]
    if (buffer == null) {
        val shrink = minOf(1f, GIF_PREVIEW_MAX_SIDE / maxOf(width, height))
        buffer = Bitmap.createBitmap(
            maxOf(1, (width * shrink).toInt()),
            maxOf(1, (height * shrink).toInt()),
            Bitmap.Config.ARGB_8888
        )
        GifFrameCache.buffers[path] = buffer
        GifFrameCache.canvases[path] = Canvas(buffer)
        GifFrameCache.shrinks[path] = shrink
    }
    val canvas = GifFrameCache.canvases[path] ?: return null
    val shrink = GifFrameCache.shrinks[path] ?: 1f
    val duration = movie.duration()
    val frameMs = if (duration <= 0) 0 else (timeMs % duration).toInt()
    movie.setTime(frameMs)
    buffer.eraseColor(Color.TRANSPARENT)
    canvas.save()
    if (shrink < 1f) canvas.scale(shrink, shrink)
    movie.draw(canvas, 0f, 0f, null)
    canvas.restore()
    return buffer.asImageBitmap()
}

private fun isGifFile(file: File): Boolean = runCatching {
    val head = ByteArray(6)
    var read = 0
    file.inputStream().use { input ->
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
    }
    read == head.size && isGifBytes(head)
}.getOrDefault(false)
