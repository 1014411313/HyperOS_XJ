package com.hyperos.xj.runtime

/** 诊断包上传结果：失败时带上原因，便于在 App 里看清卡在哪一步。 */
sealed interface FeedbackUpload {
    data class Ok(val path: String) : FeedbackUpload
    data class Failed(val reason: String) : FeedbackUpload
}

/**
 * 读仓库里的 feedback_endpoint.json 拿中转地址（需要 INTERNET 权限）。
 * 读不到时由实现方回退到内置默认地址。
 */
expect suspend fun feedbackEndpoint(): String?

/**
 * 上传诊断包：国内优先直连 Gitee 私有收包仓库，失败才退回 Cloudflare 中转（→ GitHub）。
 * 两条都不通时，调用方应引导用户改用「分享诊断包」。
 */
expect suspend fun uploadDiagnosticPackage(filePath: String, note: String, device: String): FeedbackUpload

/** 通过系统分享把诊断包发给作者（微信/QQ 等），网络不通时用；失败返回 false。 */
expect fun shareDiagnosticPackage(filePath: String): Boolean
