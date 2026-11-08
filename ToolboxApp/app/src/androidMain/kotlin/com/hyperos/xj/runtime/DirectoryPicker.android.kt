package com.hyperos.xj.runtime

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object AndroidDirectoryPicker {
    private var launcher: ActivityResultLauncher<Uri?>? = null
    private var pending: (String?) -> Unit = {}

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            val callback = pending
            pending = {}
            callback(uri?.let { toAbsolutePath(activity, it) })
        }
    }

    fun launch(onResult: (String?) -> Unit): Boolean {
        val launcher = launcher ?: return false
        pending = onResult
        launcher.launch(null)
        return true
    }

    private fun toAbsolutePath(activity: ComponentActivity, uri: Uri): String? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!docId.startsWith("primary:")) {
            Toast.makeText(
                activity,
                "该存储位置（SD 卡/OTG/下载等）暂不支持，请选择内部存储中的目录",
                Toast.LENGTH_LONG
            ).show()
            return null
        }
        val relative = docId.removePrefix("primary:").trim('/')
        val root = Environment.getExternalStorageDirectory().absolutePath
        return if (relative.isEmpty()) root else "$root/$relative"
    }
}

actual suspend fun pickDirectoryPath(): String? =
    suspendCancellableCoroutine { continuation ->
        val launched = AndroidDirectoryPicker.launch { path ->
            if (continuation.isActive) continuation.resume(path)
        }
        if (!launched && continuation.isActive) continuation.resume(null)
    }
