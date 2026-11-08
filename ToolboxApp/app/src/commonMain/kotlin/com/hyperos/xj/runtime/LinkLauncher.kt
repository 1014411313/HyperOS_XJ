package com.hyperos.xj.runtime

/** 用系统浏览器打开链接；取不到环境或无处理器时静默忽略。 */
expect fun openUrl(url: String)
