# 公益站模型接入 AiCode 操作指南

适用于两个公益 API 站：**AgentRouter**、**小欢公益（free-llm.cups.moe）**。
两份配置互不影响，可同时存在，按需切换。

整理时间：2026-11-18。站点随时可能调整，以各自控制台/公告为准。

---

## 一、AgentRouter

站点：<https://agentrouter.org>　文档：<https://agentrouter.org/docs/index.html>

### 1. 准备 Key

登录 AgentRouter 控制台（文档给的取令牌入口：`https://ps.air-outer.com/console/token`），创建令牌，形如 `sk-xxxxxxxx`。

### 2. 确认你的令牌能用哪些模型

这家站的令牌有模型权限，文档列出的名字不一定对你的令牌开放。先查一次：

```bash
export KEY='sk-你的令牌'
curl -s https://agentrouter.org/v1/models \
  -H "Authorization: Bearer $KEY" \
  -H 'User-Agent: claude-cli/2.0.1 (external, cli)'
```

返回的 `data[].id` 才是可用模型。实测某令牌只返回 `gpt-5.6-sol`、`gpt-6.1-sol` 两个；用列表外的模型（如 `claude-opus-5`）会得到
`403 该令牌无权访问模型`。

### 3. AiCode 中新建供应商

「设置 → AI 供应商 → 右上角 + → 自定义供应商」，按下表填：

| 项 | 值 |
| --- | --- |
| 名称 | `AgentRouter` |
| 上游格式 | `OpenAI` |
| API Key | 上一步的令牌 |
| Base URL | `https://agentrouter.org/v1` |
| 选项 → Response API (新版) | **开启** |
| 选项 → 完整 URL | 关闭 |

### 4. 关键一步：自定义请求头

这家站会校验客户端身份，请求里没有 Claude Code 的 User-Agent 一律回
`401 unauthorized client detected`。

「选项 → 自定义请求头 → 添加请求头」：

| Header 名 | 值 |
| --- | --- |
| `User-Agent` | `claude-cli/2.0.1 (external, cli)` |

> 注意：供应商里那个独立的「User-Agent」字段（如果界面上有）不参与实际请求，只认「自定义请求头」这一项，别填错地方。

### 5. 添加模型

切到「模型」标签 → 手动添加（或「拉取模型」）：

- `gpt-5.6-sol`
- `gpt-6.1-sol`

默认模型选 `gpt-5.6-sol`，然后对任意一个点「测试」，通过即可在聊天框选用。

### 6. 为什么必须开 Response API

这两个模型开着思考时，`/v1/chat/completions` 不支持函数调用，会报
`Function tools with reasoning_effort are not supported ... use /v1/responses`。
AiCode 全程依赖工具调用，所以必须走 `/v1/responses`，即勾选「Response API (新版)」，
Base URL 保持带 `/v1`，AiCode 会拼出 `https://agentrouter.org/v1/responses`。

---

## 二、小欢公益（free-llm.cups.moe）

站点：<https://free-llm.cups.moe>　说明：<https://www.cups.moe/archives/free-llm-api.html>

### 1. 注册与额度

- 只支持 **Linux.do / GitHub** 账号 OAuth 登录，登录送 200 次使用额度。
- 剩余额度 ≤ 50 次时，去上面那篇博文评论区留言报 ID 申请补充，一次 +200，人工审核。
- 站点限流 **每分钟 2 次请求（RPM=2）**，超了返回 429。

### 2. 配置供应商

「设置 → AI 供应商 → +」，按下表填：

| 项 | 值 |
| --- | --- |
| 名称 | `小欢公益` |
| 上游格式 | `OpenAI` |
| API Key | 站内创建的令牌 |
| Base URL | `https://free-llm.cups.moe/v1` |
| 选项 → Response API (新版) | **关闭** |
| 自定义请求头 | 不需要 |

这家站是标准 New API，**不做客户端 UA 校验**（浏览器 UA、SDK UA、curl 默认 UA
得到的响应一致），不需要像 AgentRouter 那样加伪装头。

### 3. 选模型

「拉取模型」可拿到站内全部模型。优先选站内公告可用的：

- DeepSeek 系：`deepseek-v3.2`、`DeepSeek-V3.2-Exp`、`DeepSeek-R1`
- 其他：`Kimi-K2`、`kimi-k2.5-instant`、`GLM-4.6`、`Qwen3-235B-A22B`、`Qwen3-Coder`

避开 `gpt-*`、`gemini-*`：站长 2026-03-12 公告写着上游挂了、预计长期不可用。

### 4. 使用限制

- 该站没有原生 function calling，靠 Toolify 用提示词模拟，工具调用不如原生稳。
  AiCode 里当普通对话模型用较合适，别指望它在编码任务里稳定调度工具。
- AiCode 一次交互可能产生多个请求（标题总结、上下文压缩等），RPM=2 很容易撞 429，
  建议当备用线路。

---

## 三、排错对照表

| 现象 | 站点 | 原因与处理 |
| --- | --- | --- |
| `401 unauthorized client detected` | AgentRouter | 缺 Claude Code 的 UA。检查「自定义请求头」里 `User-Agent: claude-cli/2.0.1 (external, cli)` 是否保存成功 |
| `403 该令牌无权访问模型` | AgentRouter | 模型不在该令牌权限内。用 `/v1/models` 查实际可用模型名，注意连字符写法 |
| `400 ... use /v1/responses` | AgentRouter | 没开「Response API (新版)」，gpt-5.6-sol / gpt-6.1-sol 不支持旧 chat 端点做工具调用 |
| `429 You have reached the request limit: maximum 2 requests in 1 minutes` | 小欢公益 | 站点限流，等 1 分钟、减少测试次数；说明鉴权已通过，配置没问题 |
| `401 Invalid token` | 小欢公益 | 令牌无效或额度用尽，站内重新建令牌/申请额度 |
| 返回 HTML（Security Verification 页面） | 小欢公益 | 请求没带 `Accept: application/json` 之类的头被前置验证页拦截；命令行测试时补上 `-H 'Accept: application/json'` |

---

## 四、命令行快速自检

AiCode 用的是 OpenAI 兼容协议，命令行的验证方式可以照抄。

AgentRouter（Response API + 工具调用）：

```bash
export KEY='sk-你的令牌'
curl -s https://agentrouter.org/v1/responses \
  -H "Authorization: Bearer $KEY" \
  -H 'User-Agent: claude-cli/2.0.1 (external, cli)' \
  -H 'content-type: application/json' \
  -d '{"model":"gpt-5.6-sol","input":"Reply OK","max_output_tokens":32}'
```

小欢公益（标准 chat completions）：

```bash
export KEY='sk-你的令牌'
curl -s https://free-llm.cups.moe/v1/chat/completions \
  -H "Authorization: Bearer $KEY" \
  -H 'Accept: application/json' \
  -H 'content-type: application/json' \
  -d '{"model":"deepseek-v3.2","messages":[{"role":"user","content":"hi"}]}'
```

---

## 五、两个站怎么分工

- **AgentRouter**：原生 Responses API + 原生函数调用，模型 `gpt-5.6-sol` /
  `gpt-6.1-sol`，适合当 AiCode 的主力编码模型。
- **小欢公益**：免费额度多、模型杂，但 RPM=2、无原生 function calling、上游经常性故障，
  适合手动聊天或临时备用。

两者都属公益线路，随时可能停服或调整，不要把长期工作流压在单一免费站上。
