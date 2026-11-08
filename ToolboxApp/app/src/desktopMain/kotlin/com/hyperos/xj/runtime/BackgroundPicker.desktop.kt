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
