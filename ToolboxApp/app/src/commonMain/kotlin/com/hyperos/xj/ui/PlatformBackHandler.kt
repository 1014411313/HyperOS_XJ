package com.hyperos.xj.ui

import androidx.compose.runtime.Composable

/** 平台返回手势/按键：Android 接系统返回，桌面端无实现。 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
