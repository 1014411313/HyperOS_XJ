package com.hyperos.xj.runtime

/** 诊断包上传结果：失败时带上原因，便于在 App 里看清卡在哪一步。 */
sealed interface FeedbackUpload {
    data class Ok(val path: String) : FeedbackUpload
    data class Failed(val reason: String) : FeedbackUpload
}

/**
 * 读仓库里的 feedback_endpoint.json 拿中转地址；注意 App 需要 INTERNET 权限。
 * 读不到时由实现方回退到内置默认地址。
 */
expect suspend fun feedbackEndpoint(): String?

/**
 * 上传诊断包：中转通道优先（任何用户都不需要 token），失败再退回本地 token 直传。
 * note 是用户填写的问题描述，device 是机型。
 */
expect suspend fun uploadDiagnosticPackage(filePath: String, note: String, device: String): FeedbackUpload

/** App 内保存的 GitHub token（fine-grained，仅本仓库 Contents 读写）；空串表示未配置。 */
expect fun loadFeedbackToken(): String

expect fun saveFeedbackToken(token: String)

/** 通过系统分享把诊断包发给作者（微信/QQ/邮件等），网络不通时用；失败返回 false。 */
expect fun shareDiagnosticPackage(filePath: String): Boolean
