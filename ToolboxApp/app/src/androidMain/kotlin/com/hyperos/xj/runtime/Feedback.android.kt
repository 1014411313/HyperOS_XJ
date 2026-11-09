package com.hyperos.xj.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val FEEDBACK_REPO = "1014411313/HyperOS_XJ"

// 内置默认中转地址：即使读不到 feedback_endpoint.json（缓存未刷新/网络受限）也能上传
private const val DEFAULT_ENDPOINT = "https://xj-feedback.1014411313.workers.dev"

private val ENDPOINT_SOURCES = listOf(
    "https://cdn.jsdelivr.net/gh/$FEEDBACK_REPO@master/feedback_endpoint.json",
    "https://gitee.com/xiaomirezhale/HyperOS_XJ/raw/master/feedback_endpoint.json",
    "https://raw.githubusercontent.com/$FEEDBACK_REPO/master/feedback_endpoint.json",
)

private val appContext: Context? by lazy {
    runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}

actual suspend fun feedbackEndpoint(): String? = withContext(Dispatchers.IO) {
    for (src in ENDPOINT_SOURCES) {
        val body = runCatching { httpGet(src, 8000, 12000) }.getOrNull() ?: continue
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        if (!url.isNullOrBlank()) return@withContext url.trim()
    }
    DEFAULT_ENDPOINT
}

actual suspend fun uploadDiagnosticPackage(
    filePath: String,
    note: String,
    device: String,
): FeedbackUpload = withContext(Dispatchers.IO) {
    val file = resolvePackage(filePath)
    if (!file.isFile) return@withContext FeedbackUpload.Failed("找不到诊断包：$filePath")
    val endpoint = feedbackEndpoint()
        ?: return@withContext FeedbackUpload.Failed("未配置上传地址")
    when (val r = postToWorker(endpoint, file, note, device)) {
        is WorkerResult.Ok -> FeedbackUpload.Ok(r.path)
        is WorkerResult.Fail -> FeedbackUpload.Failed(r.reason)
    }
}


/** App 无 /sdcard 读权限：diag.sh 会在 App 私有目录放一份副本，优先用它。 */
private fun resolvePackage(path: String): File {
    val ctx = AndroidBackgroundPicker.appContextOrNull()
    val name = path.substringAfterLast('/')
    val copy = ctx?.let { File(it.filesDir, "diag/$name") }
    return if (copy != null && copy.isFile) copy else File(path)
}

private sealed interface WorkerResult {
    data class Ok(val path: String) : WorkerResult
    data class Fail(val reason: String) : WorkerResult
}

private fun postToWorker(endpoint: String, file: File, note: String, device: String): WorkerResult {
    return try {
        val boundary = "----xj" + System.currentTimeMillis()
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 90000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { out ->
            fun field(name: String, value: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("note", note)
            field("device", device)
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
            )
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            WorkerResult.Fail("HTTP $code ${body.take(120)}")
        } else {
            val path = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            WorkerResult.Ok(path ?: "feedback/")
        }
    } catch (t: Throwable) {
        WorkerResult.Fail(t.javaClass.simpleName + " " + (t.message ?: ""))
    }
}

private fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
    }
    return try {
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

/** 复制到 files/share 再用 FileProvider 分享：/sdcard/Download 不在 App 可直接分享的范围内。 */
actual fun shareDiagnosticPackage(filePath: String): Boolean {
    val ctx = AndroidBackgroundPicker.appContextOrNull() ?: return false
    return runCatching {
        val src = resolvePackage(filePath)
        if (!src.isFile) return false
        val dir = File(ctx.filesDir, "share").apply { mkdirs() }
        val dst = File(dir, src.name)
        src.copyTo(dst, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", dst
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "诊断包 " + dst.name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(intent, "发送诊断包").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
        true
    }.getOrDefault(false)
}
