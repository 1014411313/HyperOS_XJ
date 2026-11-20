package com.hyperos.xj.runtime

fun statusRefreshInterval(selectedTab: Int, previewVisible: Boolean): Long =
    if (selectedTab == 0 || (selectedTab == 3 && previewVisible)) 1_000L else 5_000L

fun statusRefreshDelay(interval: Long, readElapsed: Long): Long =
    (interval - readElapsed).coerceAtLeast(0L)
