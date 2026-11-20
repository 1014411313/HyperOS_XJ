package com.hyperos.xj.runtime

fun main() {
    val full = mapOf("cap" to "0", "status" to "Discharging", "temp" to "300", "volt" to "4000000", "curr" to "1000", "ts" to "100")
    check(snapshotHasFreshCore(full, 100_000))
    check(snapshotHasFreshCore(full, 101_500))
    check(!snapshotHasFreshCore(full, 101_501))
    check(!snapshotHasFreshCore(full, 99_999))
    check(!snapshotHasFreshCore(full - "curr", 100_000))
    check(!snapshotHasFreshCore(full - "ts", 100_000))
    check(!snapshotHasFreshCore(full + ("ts" to "invalid"), 100_000))
    check(!snapshotHasFreshCore(null, 100_000))
    check(statusRefreshInterval(0, false) == 1_000L)
    check(statusRefreshInterval(3, true) == 1_000L)
    check(statusRefreshInterval(3, false) == 5_000L)
    for (page in listOf(1, 2, 4)) check(statusRefreshInterval(page, true) == 5_000L)
    check(statusRefreshDelay(1_000L, 240L) == 760L)
    check(statusRefreshDelay(1_000L, 999L) == 1L)
    check(statusRefreshDelay(1_000L, 1_000L) == 0L)
    check(statusRefreshDelay(1_000L, 2_500L) == 0L)
    check(statusRefreshDelay(5_000L, 240L) == 4_760L)
    println("PASS 19 snapshot freshness/core and refresh interval behavior checks")
}
