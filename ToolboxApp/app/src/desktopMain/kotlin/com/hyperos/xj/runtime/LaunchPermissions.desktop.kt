package com.hyperos.xj.runtime

actual fun notificationsGranted(): Boolean = true

actual fun requestNotifications() = Unit

actual fun batteryOptimizationIgnored(): Boolean = true

actual fun requestIgnoreBatteryOptimizations() = Unit

actual fun isLsposedInstalled(): Boolean = false

actual fun openLsposedManager(): Boolean = false

actual fun loadLaunchFlags(): LaunchFlags = LaunchFlags()

actual fun saveLaunchFlags(flags: LaunchFlags) = Unit
