package com.hyperos.xj.runtime

import java.io.File
import java.io.FileNotFoundException
import kotlin.math.abs

/** 权限被拒：本次采样不再继续探其它节点，避免一次刷新刷出一串 denial。 */
internal class NodeDenied : Exception()

/**
 * 数据源读取与冷却策略（纯 JDK，不依赖 Android，可用真实 JVM 测试）。
 *
 * 约定：
 * - 读成功即解除该路径冷却，"0" 是有效值；
 * - 权限拒绝（EACCES/SecurityException）让该路径长退避，并中断本轮采样；
 * - 只有文件不存在（ENOENT）走短退避，其它 IO 异常按长退避；
 * - 冷却到期只重试一次，成功或失败都重新计时。
 */
internal class StatusSourceCache(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val readText: (File) -> String = { it.readText() },
) {
    private val cooled = HashMap<String, Long>()

    fun cooledDown(path: String): Boolean {
        val until = cooled[path] ?: return false
        if (clock() < until) return true
        cooled.remove(path)
        return false
    }

    fun markReadable(path: String) {
        cooled.remove(path)
    }

    fun markUnreadable(path: String, retryMs: Long) {
        cooled[path] = clock() + retryMs
    }

    /**
     * 不先做 `File.isFile` 预检：预检会把 EACCES 当成"不存在"，吞掉权限拒绝后继续逐个探节点。
     * 直接读，让异常暴露原因。
     */
    fun readNode(file: File, deniedRetryMs: Long, missingRetryMs: Long): String? {
        val path = file.path
        if (cooledDown(path)) return null
        return try {
            val text = readText(file).trim()
            if (text.isEmpty()) {
                markUnreadable(path, missingRetryMs)
                null
            } else {
                markReadable(path)
                text
            }
        } catch (t: Throwable) {
            markUnreadable(path, if (isMissing(t)) missingRetryMs else deniedRetryMs)
            if (isDenied(t)) throw NodeDenied()
            null
        }
    }

    /** 取第一个可读且新鲜的快照；权限拒绝只按长退避，不能被后面的分支改成短退避。 */
    fun readSnapshot(
        files: List<File>,
        staleMs: Long,
        deniedRetryMs: Long,
        missingRetryMs: Long,
        parse: (String) -> Map<String, String>,
    ): Map<String, String>? {
        for (file in files) {
            val path = file.path
            if (cooledDown(path)) continue
            val raw = try {
                readText(file)
            } catch (t: Throwable) {
                markUnreadable(path, if (isMissing(t)) missingRetryMs else deniedRetryMs)
                continue
            }
            val map = parse(raw)
            if (map.isEmpty()) {
                markUnreadable(path, missingRetryMs)
                continue
            }
            markReadable(path)
            val ts = map["ts"]?.toLongOrNull()?.times(1000) ?: continue
            if (abs(clock() - ts) > staleMs) continue
            return map
        }
        return null
    }
}

internal fun isDenied(t: Throwable): Boolean {
    if (t is SecurityException) return true
    val msg = t.message ?: return false
    return msg.contains("EACCES") || msg.contains("Permission denied")
}

internal fun isMissing(t: Throwable): Boolean =
    t is FileNotFoundException && !isDenied(t)

/** 关键字段齐了才算一份完整实时读数：缺字段不能当 0。 */
internal fun hasLiveCore(live: Map<String, String>): Boolean =
    CORE_LIVE_KEYS.all { !live[it].isNullOrBlank() }

internal fun snapshotHasFreshCore(snapshot: Map<String, String>?, now: Long): Boolean =
    snapshot != null && hasLiveCore(snapshot) &&
        snapshot["ts"]?.toLongOrNull()?.let { now - it * 1000 in 0..1_500L } == true

private val CORE_LIVE_KEYS = listOf("cap", "status", "temp", "volt", "curr")

/** 快照 / sysfs 实时 / root 基底三路的取舍。 */
internal enum class LiveSource {
    SNAPSHOT_WITH_LIVE,
    SNAPSHOT,
    LIVE,
    ROOT_WITH_LIVE,
    ROOT,
    PARTIAL_LIVE,
    NONE,
}

internal fun pickLiveSource(
    hasSnapshot: Boolean,
    live: Map<String, String>?,
    rootBase: Map<String, String>?,
): LiveSource = when {
    hasSnapshot && live != null -> LiveSource.SNAPSHOT_WITH_LIVE
    hasSnapshot -> LiveSource.SNAPSHOT
    live != null && hasLiveCore(live) -> LiveSource.LIVE
    rootBase != null && live != null -> LiveSource.ROOT_WITH_LIVE
    rootBase != null -> LiveSource.ROOT
    live != null -> LiveSource.PARTIAL_LIVE
    else -> LiveSource.NONE
}
