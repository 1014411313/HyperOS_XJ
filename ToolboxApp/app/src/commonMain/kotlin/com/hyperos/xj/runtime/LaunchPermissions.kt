package com.hyperos.xj.runtime

/** 开屏权限提示的"不再提示"状态。 */
data class LaunchFlags(
    val dismissed: Boolean = false,
    val lsposedConfirmed: Boolean = false,
)

/** Android 13+ 的通知权限；低版本或无权限系统返回 true。 */
expect fun notificationsGranted(): Boolean

/** 申请通知权限（Android 侧弹系统授权框）；桌面端空实现。 */
expect fun requestNotifications()

/** 是否已加入电池优化白名单。 */
expect fun batteryOptimizationIgnored(): Boolean

/** 打开系统的电池优化设置（引导用户手动放行）。 */
expect fun requestIgnoreBatteryOptimizations()

/** 是否安装了 LSPosed 管理器。 */
expect fun isLsposedInstalled(): Boolean

/** 打开 LSPosed 管理器；未安装或打开失败返回 false。 */
expect fun openLsposedManager(): Boolean

expect fun loadLaunchFlags(): LaunchFlags

expect fun saveLaunchFlags(flags: LaunchFlags)

/** 诊断包里的问题描述（生成诊断包时随包带走）；传空串表示清除。 */
expect suspend fun writeDiagNote(text: String)
