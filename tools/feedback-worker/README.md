# 诊断包接收 Worker（可选，推荐用更简单的做法）

**推荐**：在 App「关于 → 诊断与日志打包」里直接粘贴 GitHub token（fine-grained，仅本仓库 Contents 读写），
App 会直接调 GitHub Contents API 上传，不需要部署下面这套 Worker。

只有在不想把 token 放进手机、希望用中转转发时才需要部署这个 Worker。

---

## 部署（约 3 分钟，手机也能做）

1. 建 token：GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate new token
   - Repository access：只选 `HyperOS_XJ`
   - Permissions → Repository permissions：**Contents: Read and write**（只要这一项）
   - 复制 token（只显示一次）

2. Cloudflare：`dash.cloudflare.com` → **Workers & Pages → Create → Workers → Start with Hello World!**
   - 名字填 `xj-feedback` → **Deploy**
   - 点 **Edit code**，清空示例代码，粘贴本目录 `worker.js` 全文 → **Deploy**

3. 同一 Worker → **Settings → Variables and Secrets → Add**（类型 **Secret**）：
   - `GH_TOKEN` = 第 1 步的 token
   - `GH_REPO` = `1014411313/HyperOS_XJ`
   - 保存后按提示 **Deploy** 一次

   （等价的命令行做法：`wrangler secret put GH_TOKEN` / `GH_REPO`，再 `wrangler deploy`）

4. 部署完页面会给出地址，形如 `https://xj-feedback.<子域>.workers.dev`，填进仓库根目录 `feedback_endpoint.json`：

   ```json
   { "url": "https://xj-feedback.<子域>.workers.dev" }
   ```

   留空则 App 只生成本地包、不上传；App 从 jsDelivr / Gitee / GitHub raw 依次读这个文件，改完不用重新发版。

## 上传后的样子

- `feedback/<时间戳>_rezhale_toolbox_diag_xxx.tar.gz`：诊断包本体
- `feedback/<时间戳>_rezhale_toolbox_diag_xxx.txt`：机型、上传时间、你在 App 里写的问题描述

作者读完、修完后会把这些文件从仓库删掉。

## 限额

- 单包上限 `wrangler.toml` 的 `MAX_BYTES`（默认 8 MiB，与 `worker.js` 一致）。
- GitHub Contents API 单文件建议 ≤ 25 MiB；诊断包一般 1–3 MiB。
