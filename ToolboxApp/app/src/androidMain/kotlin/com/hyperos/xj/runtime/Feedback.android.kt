package com.hyperos.xj.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val FEEDBACK_REPO = "1014411313/HyperOS_XJ"

// 国内首选通道：直连 Gitee 私有收包仓库（国内可达，不经第三方中转）。
private const val GITEE_REPO = "xiaomirezhale/xj-feedback"
private const val GITEE_CONTENTS_API = "https://gitee.com/api/v5/repos/$GITEE_REPO/contents"

// 写入令牌以 AES-128-CBC 密文内置（前 16 字节为 IV），使用时才解密。
// 说明：客户端加密只提高读取门槛，不能真正防逆向；权限侧靠「令牌只对私有收包仓库可写，可疑即吊销」。
private const val TOKEN_ENC = "lmpOicj8eSEkIYEW8JG/v4AzXQM9RjxS99EUTyRZdD1bb3nOSKRTu9ciEJP7nZP61O7uUpYCbOsw0ephJ+uA2A=="

// 密钥字节经异或保存，避免明文密钥与密文在产物里成对出现。
private val KEY_MASK = byteArrayOf(
    0x28.toByte(), 0x3F.toByte(), 0x20.toByte(), 0x32.toByte(), 0x3B.toByte(), 0x36.toByte(), 0x3F.toByte(), 0x77.toByte(),
    0x29.toByte(), 0x3F.toByte(), 0x39.toByte(), 0x28.toByte(), 0x3F.toByte(), 0x2E.toByte(), 0x6B.toByte(), 0x6C.toByte(),
)

private fun builtinToken(): String = runCatching {
    val raw = android.util.Base64.decode(TOKEN_ENC, android.util.Base64.DEFAULT)
    if (raw.size <= 16) return@runCatching ""
    val key = ByteArray(KEY_MASK.size) { (KEY_MASK[it].toInt() xor 0x5A).toByte() }
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(raw, 0, 16))
    String(cipher.doFinal(raw, 16, raw.size - 16), Charsets.UTF_8)
}.getOrDefault("")

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
    // 1) 国内直连 Gitee 私有收包仓库
    val gitee = uploadToGitee(file, note, device)
    if (gitee is WorkerResult.Ok) return@withContext FeedbackUpload.Ok(gitee.path)
    // 2) 兑底：Cloudflare 中转 → GitHub（部分网络仍可用）
    val endpoint = feedbackEndpoint()
    if (endpoint == null) return@withContext FeedbackUpload.Failed((gitee as WorkerResult.Fail).reason)
    when (val r = postToWorker(endpoint, file, note, device)) {
        is WorkerResult.Ok -> FeedbackUpload.Ok(r.path)
        is WorkerResult.Fail -> FeedbackUpload.Failed("$r; Gitee: ${(gitee as WorkerResult.Fail).reason}")
    }
}

/** 直传 Gitee Contents API（国内可达）：先传包体，再传同名的机型/描述 txt。 */
private fun uploadToGitee(file: File, note: String, device: String): WorkerResult {
    if (file.length() > MAX_GITEE_BYTES) {
        return WorkerResult.Fail("包体积 ${file.length() / 1024}KB 超出直传上限")
    }
    val token = builtinToken()
    if (token.isBlank()) return WorkerResult.Fail("内置令牌不可用")
    val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.US)
        .format(java.util.Date())
    val pkgPath = "feedback/${stamp}_${file.name}"
    return try {
        val r1 = giteePutContent(token, pkgPath, file.readBytes())
        if (r1 is WorkerResult.Fail) return r1
        val meta = "机型: $device\n上传: $stamp\n\n问题描述:\n${note.ifBlank { "（未填写）" }}\n"
        val metaPath = pkgPath.substringBeforeLast('.') + ".txt"
        val r2 = giteePutContent(token, metaPath, meta.toByteArray())
        if (r2 is WorkerResult.Fail) return r2
        WorkerResult.Ok(pkgPath)
    } catch (t: Throwable) {
        WorkerResult.Fail(t.javaClass.simpleName + " " + (t.message ?: ""))
    }
}

private const val MAX_GITEE_BYTES = 8L * 1024 * 1024

private fun giteePutContent(token: String, path: String, bytes: ByteArray): WorkerResult {
    return try {
        val payload = "{\"access_token\":\"$token\",\"content\":\"" +
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) +
            "\",\"message\":\"feedback: $path\",\"branch\":\"master\"}"
        val conn = (URL("$GITEE_CONTENTS_API/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 120000
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        }
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code in 200..299) WorkerResult.Ok(path)
        else WorkerResult.Fail("Gitee HTTP $code ${body.take(120)}")
    } catch (t: Throwable) {
        WorkerResult.Fail("Gitee " + t.javaClass.simpleName + " " + (t.message ?: ""))
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
