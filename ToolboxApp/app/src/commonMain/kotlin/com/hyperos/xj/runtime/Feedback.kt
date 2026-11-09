package com.hyperos.xj.runtime

/** 读仓库里的 feedback_endpoint.json，拿到诊断包上传地址；未配置或读取失败返回 null。 */
expect suspend fun feedbackEndpoint(): String?

/**
 * 把诊断包上传到中转（Worker），成功返回反馈链接（issue 地址）。
 * note 是用户在 App 里写的问题描述，device 是机型，都会随包带过去。
 */
expect suspend fun uploadDiagnosticPackage(filePath: String, note: String, device: String): String?
