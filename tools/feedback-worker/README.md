# 诊断包接收 Worker

App 侧「诊断与日志打包」会把诊断包 POST 到这里，Worker 把包提交到仓库 `feedback/` 目录，
并建一个 Issue（GitHub 会给你发通知）。你处理完关闭 issue，工作流会自动删掉对应的包。

## 部署（约 5 分钟）

1. 建 token：GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate new token
   - Repository access：只选 `HyperOS_XJ`
   - Permissions：**Contents: Read and write**、**Issues: Read and write**
   - 复制 token（只显示一次）

2. 装 wrangler 并登录：

   ```bash
   npm install -g wrangler
   wrangler login
   ```

3. 在 `tools/feedback-worker/` 下设置密钥并部署：

   ```bash
   cd tools/feedback-worker
   wrangler secret put GH_TOKEN     # 粘贴第 1 步的 token
   wrangler secret put GH_REPO      # 输入 1014411313/HyperOS_XJ
   wrangler deploy
   ```

   部署成功会打印形如 `https://xj-feedback.<你的子域>.workers.dev` 的地址。

4. 把地址填进仓库根目录的 `feedback_endpoint.json`：

   ```json
   { "url": "https://xj-feedback.<你的子域>.workers.dev" }
   ```

   提交后 App 会自动读到（走 jsDelivr / Gitee / GitHub raw 依次尝试）；留空则只生成本地包、不上传。

## 通知与清理

- **通知**：Worker 建 Issue 时 GitHub 会给仓库所有者发邮件；你也能在仓库 Issues 里看到，标题带机型与时间。
- **自动删除**：关闭对应 issue → `.github/workflows/feedback-cleanup.yml` 从 issue 正文解析文件名并 `git rm`；
  另有每天 03:00 的定时任务清理 30 天前的遗留包。

## 限额

- 单包上限由 `wrangler.toml` 的 `MAX_BYTES` 控制（默认 8 MiB，与 `worker.js` 一致）。
- GitHub Contents API 单文件建议不超过 25 MiB；诊断包通常 1–3 MiB。
- Cloudflare 免费版每天 10 万次请求，够用。
