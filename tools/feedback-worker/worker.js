// 诊断包接收中转：App POST multipart(file, note, device) →
//   用 GitHub Contents API 把包写进仓库 feedback/ 目录，返回文件路径。
// 作者（AI）下次直接在仓库里读这个包，处理完自行删除；不需要通知那套。
//
// 需要的 secrets：
//   GH_TOKEN  fine-grained token，权限：Contents: Read and write（仅本仓库）
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

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("method not allowed", { status: 405 });
    }
    // GH_REPO 有内置默认值，只配 GH_TOKEN 也能跑
    const repo = env.GH_REPO || "1014411313/HyperOS_XJ";
    if (!env.GH_TOKEN) {
      return new Response("missing GH_TOKEN secret", { status: 500 });
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
    // 问题描述与机型写进同名 .txt，作者读包时先看这个
    let metaPath = path.replace(/\.[^.]+$/, "") + ".txt";
    // 上传的文件本身若以 .txt 结尾，meta 会撞同名（GitHub 更新已有文件要 sha）
    if (metaPath === path) metaPath = path + ".meta.txt";
    const meta = `机型: ${device}\n上传: ${stamp}\n\n问题描述:\n${note || "（未填写）"}\n`;

    const put = async (p, content) => {
      const res = await fetch(`https://api.github.com/repos/${repo}/contents/${p}`, {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${env.GH_TOKEN}`,
          Accept: "application/vnd.github+json",
          "User-Agent": "xj-feedback-worker",
          "X-GitHub-Api-Version": "2022-11-28",
        },
        body: JSON.stringify({ message: `feedback: ${p}`, content }),
      });
      return res;
    };

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
