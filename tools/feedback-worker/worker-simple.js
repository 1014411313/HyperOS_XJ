// 免配置版：把 token 填在第一行即可，不需要在 Cloudflare 里配 Variables/Secrets。
// token 只存在这个 Worker 脚本里（只有能登录该 Cloudflare 账号的人能看到），随时可在 GitHub 作废重建。
// 注意：这份代码只在本仓库留存占位符，真实 token 不要提交到仓库。

const GH_TOKEN = "在这里粘贴你的GitHub_token";
const GH_REPO = "1014411313/HyperOS_XJ";

const MAX_NAME = 120;

function b64(bytes) {
  let out = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    out += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  return btoa(out);
}

function safeName(name) {
  const base = (name || "diag.tar.gz").replace(/[^\w.\-]/g, "_").slice(-MAX_NAME);
  return base || "diag.tar.gz";
}

export default {
  async fetch(request) {
    if (request.method !== "POST") {
      return new Response("method not allowed", { status: 405 });
    }
    if (GH_TOKEN.indexOf("在这里") === 0) {
      return new Response("missing GH_TOKEN", { status: 500 });
    }
    const maxBytes = 8 * 1024 * 1024;

    let form;
    try {
      form = await request.formData();
    } catch {
      return new Response("bad multipart", { status: 400 });
    }

    const file = form.get("file");
    if (!file || typeof file === "string") {
      return new Response("missing file", { status: 400 });
    }
    if (file.size > maxBytes) {
      return new Response(`file too large (max ${maxBytes} bytes)`, { status: 413 });
    }

    const note = String(form.get("note") || "").slice(0, 4000);
    const device = String(form.get("device") || "未知机型").slice(0, 200);
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const path = `feedback/${stamp}_${safeName(file.name)}`;
    let metaPath = path.replace(/\.[^.]+$/, "") + ".txt";
    // 上传的文件本身若以 .txt 结尾，meta 会撞同名（GitHub 更新已有文件要 sha）
    if (metaPath === path) metaPath = path + ".meta.txt";
    const meta = `机型: ${device}\n上传: ${stamp}\n\n问题描述:\n${note || "（未填写）"}\n`;

    const put = async (p, content) => {
      return await fetch(`https://api.github.com/repos/${GH_REPO}/contents/${p}`, {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${GH_TOKEN}`,
          Accept: "application/vnd.github+json",
          "User-Agent": "xj-feedback-worker",
          "X-GitHub-Api-Version": "2022-11-28",
        },
        body: JSON.stringify({ message: `feedback: ${p}`, content }),
      });
    };

    const bytes = new Uint8Array(await file.arrayBuffer());
    const putFile = await put(path, b64(bytes));
    if (!putFile.ok) {
      return new Response(`upload failed: ${putFile.status} ${await putFile.text()}`, { status: 502 });
    }
    const putMeta = await put(metaPath, b64(new TextEncoder().encode(meta)));
    if (!putMeta.ok) {
      return new Response(`meta failed: ${putMeta.status} ${await putMeta.text()}`, { status: 502 });
    }

    return new Response(JSON.stringify({ ok: true, file: path, note: metaPath }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  },
};
