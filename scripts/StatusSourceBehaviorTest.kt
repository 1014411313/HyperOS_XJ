package com.hyperos.xj.runtime

import java.io.File
import java.io.FileNotFoundException

/**
 * 数据源读/冷却策略的真实 JVM 行为测试（编译 StatusSourceCache.kt 一起跑，不依赖 Android）。
 * 由 scripts/status_source_test.py 驱动。
 */

private var fails = 0

private fun check(ok: Boolean, what: String) {
    if (ok) {
        println("PASS $what")
    } else {
        println("FAIL $what")
        fails++
    }
}

private fun eacces(file: File) =
    FileNotFoundException("${file.path}: open failed: EACCES (Permission denied)")

private fun enoent(file: File) =
    FileNotFoundException("${file.path}: open failed: ENOENT (No such file or directory)")

fun main() {
    eaccesStopsAndCools()
    enoentShortCooldown()
    snapshotDeniedKeepsLongCooldown()
    snapshotMissingUsesShortCooldown()
    trueZeroKept()
    recoversAfterCooldown()
    partialLiveUsesRootBase()
    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    if (fails != 0) kotlin.system.exitProcess(1)
}

/** 1) 权限拒绝：抛 NodeDenied 中断本轮，节点长退避，到期只重试一次。 */
private fun eaccesStopsAndCools() {
    var now = 0L
    var calls = 0
    val cache = StatusSourceCache({ now }, { file -> calls++; throw eacces(file) })
    val f = File("/sys/class/power_supply/battery/capacity")
    var denied = false
    try {
        cache.readNode(f, 60_000L, 5_000L)
    } catch (_: NodeDenied) {
        denied = true
    }
    check(denied, "EACCES 抛 NodeDenied（中断本轮采样）")
    check(cache.cooledDown(f.path), "EACCES 后节点进冷却")
    cache.readNode(f, 60_000L, 5_000L)
    check(calls == 1, "EACCES 冷却期内不再读（calls=$calls）")
    now = 60_001L
    var deniedAgain = false
    try {
        cache.readNode(f, 60_000L, 5_000L)
    } catch (_: NodeDenied) {
        deniedAgain = true
    }
    check(deniedAgain && calls == 2, "EACCES 到期只重试一次")
}

/** 2) 节点不存在：返回 null 不中断，短退避后重探。 */
private fun enoentShortCooldown() {
    var now = 0L
    var calls = 0
    val cache = StatusSourceCache({ now }, { file -> calls++; throw enoent(file) })
    val f = File("/sys/class/power_supply/battery/voltage_avg")
    check(cache.readNode(f, 60_000L, 5_000L) == null, "ENOENT 返回 null 不抛异常")
    cache.readNode(f, 60_000L, 5_000L)
    check(calls == 1, "ENOENT 冷却期内不重试")
    now = 4_999L
    cache.readNode(f, 60_000L, 5_000L)
    check(calls == 1, "ENOENT 5s 内仍不重试")
    now = 5_000L
    cache.readNode(f, 60_000L, 5_000L)
    check(calls == 2, "ENOENT 满 5s 重探一次")
}

/** 3) 快照权限拒绝只能长退避：catch 的 60s 不能被后续 5s 覆盖。 */
private fun snapshotDeniedKeepsLongCooldown() {
    var now = 0L
    var calls = 0
    val cache = StatusSourceCache({ now }, { file -> calls++; throw eacces(file) })
    val f = File("/data/local/tmp/xj_status.json")
    val first = cache.readSnapshot(listOf(f), 15_000L, 60_000L, 5_000L) { emptyMap<String, String>() }
    check(first == null, "快照 EACCES 返回 null")
    now = 6_000L
    cache.readSnapshot(listOf(f), 15_000L, 60_000L, 5_000L) { emptyMap<String, String>() }
    check(calls == 1, "快照 EACCES 6s 后仍不重读（60 未被 5 覆盖）")
    now = 60_000L
    cache.readSnapshot(listOf(f), 15_000L, 60_000L, 5_000L) { emptyMap<String, String>() }
    check(calls == 2, "快照 EACCES 满 60s 重试")
}

/** 3b) 快照文件还没生成：短退避。 */
private fun snapshotMissingUsesShortCooldown() {
    var now = 0L
    var calls = 0
    val cache = StatusSourceCache({ now }, { file -> calls++; throw enoent(file) })
    val f = File("/storage/emulated/0/Android/data/com.hyperos.xj/files/status.json")
    cache.readSnapshot(listOf(f), 15_000L, 60_000L, 5_000L) { emptyMap<String, String>() }
    now = 4_999L
    cache.readSnapshot(listOf(f), 15_000L, 60_000L, 5_000L) { emptyMap<String, String>() }
    check(calls == 1, "缺快照 5s 内不重读")
    now = 5_000L
    cache.readSnapshot(listOf(f), 15_000L, 60_000L, 5_000L) { emptyMap<String, String>() }
    check(calls == 2, "缺快照满 5s 重读")
}

/** 4) 读取成功的真 0 必须保留，不能当缺失。 */
private fun trueZeroKept() {
    var calls = 0
    val cache = StatusSourceCache({ 0L }, { calls++; "0\n" })
    val f = File("/sys/class/power_supply/battery/current_now")
    check(cache.readNode(f, 60_000L, 5_000L) == "0", "真 0 保留为 0")
    check(!cache.cooledDown(f.path), "读到 0 不算缺失、不进冷却")
    cache.readNode(f, 60_000L, 5_000L)
    check(calls == 2, "0 值每次都能读")
}

/** 5) 权限恢复后自动重新读到值。 */
private fun recoversAfterCooldown() {
    var now = 0L
    var calls = 0
    val cache = StatusSourceCache({ now }, { file ->
        calls++
        if (calls == 1) throw eacces(file) else "42\n"
    })
    val f = File("/sys/class/power_supply/battery/capacity")
    try {
        cache.readNode(f, 60_000L, 5_000L)
    } catch (_: NodeDenied) {
    }
    now = 60_001L
    check(cache.readNode(f, 60_000L, 5_000L) == "42", "权限恢复后重新读到值")
}

/** 6) 部分字段的实时值不能当完整读数：有 root 基底就合并，没有才标部分字段。 */
private fun partialLiveUsesRootBase() {
    val partial = mapOf("cap" to "55")
    check(
        pickLiveSource(false, partial, mapOf("temp" to "300")) == LiveSource.ROOT_WITH_LIVE,
        "部分 live + root 基底 → 合并（缺字段不当 0）",
    )
    check(
        pickLiveSource(false, partial, null) == LiveSource.PARTIAL_LIVE,
        "部分 live 无 root → 标部分字段",
    )
    val core = mapOf(
        "cap" to "55", "status" to "Charging", "temp" to "300", "volt" to "4000000", "curr" to "0",
    )
    check(pickLiveSource(false, core, null) == LiveSource.LIVE, "关键键齐且 curr=0 算完整实时")
    check(pickLiveSource(true, partial, null) == LiveSource.SNAPSHOT_WITH_LIVE, "有快照时实时值合并")
    check(pickLiveSource(false, null, null) == LiveSource.NONE, "三路都无")
}
