package com.hyperos.xj.runtime

import androidx.compose.ui.graphics.ImageBitmap

expect suspend fun pickBackgroundImageBytes(): ByteArray?

expect fun decodeBackgroundImage(bytes: ByteArray): ImageBitmap?
