package com.hyperos.xj.runtime

import androidx.compose.ui.graphics.ImageBitmap

expect suspend fun pickBackgroundImageBytes(): ByteArray?

expect fun decodeBackgroundImage(bytes: ByteArray): ImageBitmap?

/**
 * 选中的图片写入背屏卡片背景文件：GIF（GIF87a/GIF89a）原格式保留，其余转码 PNG。
 * 成功返回引擎侧路径（/data/local/tmp/...），失败返回 null。
 */
expect suspend fun writeRearBackgroundImage(bytes: ByteArray): String?

/** 读取背景图的 App 侧本地副本字节；无副本时返回 null。 */
expect suspend fun readRearCardBgImageBytes(): ByteArray?

/**
 * 背景副本是 GIF 时返回其 App 侧预览路径，否则返回 null。
 * restoreFromEngine 为真且副本缺失时，先经 su 从引擎侧 /data/local/tmp/xj_rear_bg.gif 补铺（IO，不阻塞调用线程）。
 */
expect suspend fun rearBgGifPreviewPath(restoreFromEngine: Boolean): String?

/**
 * 内置默认动图的 App 副本预览路径（与用户自选图分文件）。
 * restoreFromEngine 为真且副本缺失时，经 su 从 /data/local/tmp/xj_rear_bg_default.gif 补铺。
 */
expect suspend fun rearDefaultBgGifPreviewPath(restoreFromEngine: Boolean): String?

/** 按时间点取 GIF 当前帧用于预览；非 GIF 或解码失败返回 null。 */
expect fun gifFrameAt(path: String, timeMs: Long): ImageBitmap?

/** 文件头判断 GIF87a / GIF89a。 */
fun isGifBytes(bytes: ByteArray): Boolean {
    if (bytes.size < 6) return false
    if (bytes[0] != 'G'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
        bytes[2] != 'F'.code.toByte() || bytes[3] != '8'.code.toByte()
    ) return false
    return (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
        bytes[5] == 'a'.code.toByte()
}
