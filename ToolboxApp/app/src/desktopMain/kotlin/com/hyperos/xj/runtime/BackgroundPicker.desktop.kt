package com.hyperos.xj.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import org.jetbrains.skia.Image

actual suspend fun pickBackgroundImageBytes(): ByteArray? {
    val dialog = FileDialog(null as Frame?, "选择背景图片", FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.files?.firstOrNull()?.readBytes()
}

actual fun decodeBackgroundImage(bytes: ByteArray): ImageBitmap? =
    Image.makeFromEncoded(bytes).toComposeImageBitmap()

actual suspend fun writeRearBackgroundImage(bytes: ByteArray): String? = null

actual suspend fun readRearCardBgImageBytes(): ByteArray? = null

actual suspend fun rearBgGifPreviewPath(restoreFromEngine: Boolean): String? = null

actual suspend fun rearDefaultBgGifPreviewPath(restoreFromEngine: Boolean): String? = null

actual fun gifFrameAt(path: String, timeMs: Long): ImageBitmap? = null

actual suspend fun pickBackgroundGif(): String? = null

actual suspend fun pickBackgroundVideo(): String? = null

actual suspend fun loadAppBackgroundImage(path: String): ImageBitmap? = null

actual suspend fun loadBuiltinAppBackground(): ImageBitmap? = null

actual suspend fun saveAppBackgroundImage(bitmap: ImageBitmap): String? = null
