// 诊断包接收中转：App POST multipart(file, note, device) →
//   1. 用 GitHub Contents API 把包写进仓库 feedback/ 目录
//   2. 建一个 Issue（GitHub 会给仓库所有者发通知），正文带文件名与问题描述
// token 只存在 Worker 的 secret 里，App 侧只有本地址。
//
// 需要的 secrets：
//   GH_TOKEN  fine-grained token，权限：Contents: Read and write、Issues: Read and write（仅本仓库）
//   GH_REPO   owner/repo，例如 1014411313/HyperOS_XJ
// 可选 vars：
//   MAX_BYTES 单包上限，默认 8 MiB

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

async function gh(env, path, init) {
  const res = await fetch(`https://api.github.com/repos/${env.GH_REPO}/${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${env.GH_TOKEN}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "xj-feedback-worker",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(init && init.headers ? init.headers : {}),
    },
  });
  return res;
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("method not allowed", { status: 405 });
    }
    const maxBytes = Number(env.MAX_BYTES || 8 * 1024 * 1024);

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

    const bytes = new Uint8Array(await file.arrayBuffer());
    const putRes = await gh(env, `contents/${path}`, {
      method: "PUT",
      body: JSON.stringify({
        message: `feedback: ${path}`,
        content: b64(bytes),
      }),
    });
    if (!putRes.ok) {
      const detail = await putRes.text();
      return new Response(`upload failed: ${putRes.status} ${detail}`, { status: 502 });
    }

    const rawUrl = `https://github.com/${env.GH_REPO}/blob/master/${path}`;
    const issueRes = await gh(env, "issues", {
      method: "POST",
      body: JSON.stringify({
        title: `[诊断包] ${device} ${stamp.slice(0, 16).replace(/-/g, ":").replace("T", " ")}`,
        body: [
          `**文件**：[\`${path}\`](${rawUrl})`,
          "",
          "**问题描述**（App 端填写）：",
          "",
          "```",
          note || "（未填写）",
          "```",
          "",
          "> 关闭本 issue 后，对应的诊断包会被工作流自动删除。",
        ].join("\n"),
      }),
    });
    if (!issueRes.ok) {
      const detail = await issueRes.text();
      return new Response(`issue failed: ${issueRes.status} ${detail}`, { status: 502 });
    }
    const issue = await issueRes.json();

    return new Response(
      JSON.stringify({ ok: true, file: path, issue: issue.number, url: issue.html_url }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  },
};
