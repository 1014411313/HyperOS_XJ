package com.hyperos.xj.runtime

/**
 * 选问题截图/照片，压缩后存进 App 私有目录；诊断包打包时由引擎一并收进包内。
 * 返回已落盘文件的绝对路径（可能少于请求张数：读取失败或用户取消）。
 */
expect suspend fun pickProblemImages(maxCount: Int): List<String>

/** 已选图片目录；引擎 diag pack 扫描它。桌面端返回空串。 */
expect fun problemImagesDir(): String

/** 清空已选图片，返回删除的张数。 */
expect fun clearProblemImages(): Int
