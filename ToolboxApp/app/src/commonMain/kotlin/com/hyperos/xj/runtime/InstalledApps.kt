package com.hyperos.xj.runtime

/** 可作为游戏温控目标的已安装应用，Pair 为 包名 to 应用名。 */
expect suspend fun installedGameCandidates(): List<Pair<String, String>>
