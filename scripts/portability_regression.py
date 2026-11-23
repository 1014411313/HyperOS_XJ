#!/usr/bin/env python3
"""跨目录接手与离线发布的隔离回归。

只读取真实项目的 scripts/doctor.py 与 scripts/release_module.sh，把副本和最小
夹具放进带空格的临时目录；PATH 前置 git/curl/zip/java/keytool 桩，因此构建、
推送、发布 API 都不会触网，不碰真机，也不改写真实清单与产物。

运行：python3 scripts/portability_regression.py
"""

import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

REPO_ROOT = Path(__file__).resolve().parents[1]
DOCTOR_SOURCE = REPO_ROOT / "scripts" / "doctor.py"
RELEASE_SOURCE = REPO_ROOT / "scripts" / "release_module.sh"
REAL_GIT = shutil.which("git")
BASE_PATH = os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin")

GITEE_REPO = "xiaomirezhale/HyperOS_XJ"
GITHUB_REPO = "1014411313/HyperOS_XJ"
FEEDBACK_REPO = "xiaomirezhale/xj-feedback"
GITEE_URL = f"https://gitee.com/{GITEE_REPO}.git"
GITHUB_URL = f"https://github.com/{GITHUB_REPO}.git"
FEEDBACK_ORIGIN = f"https://gitee.com/{FEEDBACK_REPO}.git"

WORKSPACE = {
    "repositories": {
        "source": {
            "directory": ".",
            "visibility": "public",
            "origin": GITEE_URL,
            "mirror": GITHUB_URL,
        },
        "feedback": {
            "directory": "../xj-feedback",
            "directory_env": "XJ_FEEDBACK_DIR",
            "visibility": "private",
            "origin": FEEDBACK_ORIGIN,
        },
    },
    "android_signing": {
        "variant": "debug",
        "keystore": "~/.android/debug.keystore",
        "certificate_sha256": "0" * 64,
    },
}

MODULE_PROP = """id=rezhale_toolbox
name=热炸了工具箱
version=3.1.66
versionCode=376
author=rezhale
description=迁移隔离回归桩模块
"""

GIT_STUB = r'''#!/usr/bin/env python3
import json, os, subprocess, sys
from pathlib import Path
from urllib.parse import urlsplit

log_dir = Path(os.environ["STUB_LOG"])
log_dir.mkdir(parents=True, exist_ok=True)
raw = sys.argv[1:]
with open(log_dir / "git.log", "a", encoding="utf-8") as fh:
    fh.write(json.dumps(raw, ensure_ascii=False) + "\n")

args = list(raw)
while len(args) >= 2 and args[0] in ("-C", "-c"):
    if args[0] == "-C":
        os.chdir(args[1])
    args = args[2:]
if not args:
    sys.exit(0)
cmd, rest = args[0], args[1:]

if cmd == "rev-parse":
    if "--show-toplevel" in rest:
        print(os.getcwd())
    elif "--show-cdup" in rest:
        print("")
    elif "--verify" in rest:
        sys.exit(1)
elif cmd in ("symbolic-ref", "diff", "add", "commit", "tag", "ls-files"):
    result = subprocess.run([os.environ["STUB_REAL_GIT"], cmd, *rest],
                            cwd=os.getcwd(), capture_output=True, text=True)
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    sys.exit(result.returncode)
elif cmd == "config":
    if "--get-all" in rest:
        print("store")
    elif rest[:1] == ["user.name"]:
        print("桩用户")
    elif rest[:1] == ["user.email"]:
        print("stub@example.invalid")
elif cmd == "credential":
    host = ""
    for line in sys.stdin.read().splitlines():
        if line.startswith("host="):
            host = line.split("=", 1)[1]
    with open(log_dir / "credential.log", "a", encoding="utf-8") as fh:
        fh.write(host + "\n")
    tokens = {"gitee.com": "stub-gitee-token", "github.com": "stub-github-token"}
    print("username=stub-user")
    print("password=" + tokens.get(host, "stub-unknown-token"))
elif cmd == "push":
    joined = " ".join(rest)
    fails = [x for x in os.environ.get("STUB_GIT_PUSH_FAIL", "").split(",") if x]
    if any(x in joined for x in fails):
        print("桩 git：推送被拒（注入）", file=sys.stderr)
        sys.exit(128)
    url = rest[0] if rest else ""
    host = urlsplit(url).hostname or ""
    info = {"host": host, "url": url, "url_has_at": "@" in url,
            "credential_helper_cleared": any(a == "credential.helper=" for a in raw)}
    askpass = os.environ.get("GIT_ASKPASS", "")
    if askpass:
        script = Path(askpass)
        secrets = [v for v in (os.environ.get("GT"), os.environ.get("GH"),
                               os.environ.get("GITEE_TOKEN"), os.environ.get("GITHUB_TOKEN"),
                               "stub-gitee-token", "stub-github-token") if v]
        text = script.read_text(encoding="utf-8") if script.is_file() else ""
        verify = {"script": str(script),
                  "contains_token": any(value in text for value in secrets),
                  "require": os.environ.get("GIT_ASKPASS_REQUIRE", ""),
                  "terminal_prompt": os.environ.get("GIT_TERMINAL_PROMPT", "")}
        users = {"gitee.com": {"oauth2", "xiaomirezhale"},
                 "github.com": {"x-access-token"}}.get(host, set())
        passwords = {"gitee.com": os.environ.get("GT", ""),
                     "github.com": os.environ.get("GH", "")}.get(host, "")
        for label, prompt in (("username", "Username for 'https://%s': " % host),
                              ("password", "Password for 'https://%s': " % host)):
            result = subprocess.run([str(script), prompt], capture_output=True,
                                    text=True, env=os.environ.copy())
            answer = result.stdout.strip()
            if label == "username":
                verify["username_ok"] = result.returncode == 0 and bool(answer) and (
                    answer in users if users else True)
            else:
                verify["password_ok"] = (result.returncode == 0 and bool(passwords)
                                         and answer == passwords)
                verify["password_from_env"] = bool(passwords)
        info["askpass"] = verify
    with open(log_dir / "push.log", "a", encoding="utf-8") as fh:
        fh.write(json.dumps(info, ensure_ascii=False) + "\n")
elif cmd == "log":
    print("abcdef0 release: 桩提交")
sys.exit(0)
'''

CURL_STUB = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
from urllib.parse import urlsplit, parse_qs

log_dir = Path(os.environ["STUB_LOG"])
log_dir.mkdir(parents=True, exist_ok=True)
args = sys.argv[1:]
out, write_fmt, method = None, "", "GET"
headers, body_path, upload_path, url = [], None, None, None
i = 0
while i < len(args):
    a = args[i]
    if a == "-o":
        out = args[i + 1]; i += 2; continue
    if a == "-w":
        write_fmt = args[i + 1]; i += 2; continue
    if a == "-X":
        method = args[i + 1]; i += 2; continue
    if a == "-H":
        headers.append(args[i + 1]); i += 2; continue
    if a in ("--connect-timeout", "--max-time"):
        i += 2; continue
    if a == "--data-binary":
        value = args[i + 1]
        body_path = value[1:] if value.startswith("@") else None
        i += 2; continue
    if a == "-F":
        value = args[i + 1]
        if value.startswith("file=@"):
            upload_path = value[len("file=@"):]
        i += 2; continue
    if a.startswith("-"):
        i += 1; continue
    url = a
    i += 1

with open(log_dir / "curl.log", "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"url": url, "method": method, "headers": headers,
                         "body": body_path, "upload": upload_path},
                        ensure_ascii=False) + "\n")

fails = [x for x in os.environ.get("STUB_CURL_FAIL", "").split(",") if x]
for token in fails:
    if url and token in url:
        print("curl: (22) 桩注入的 HTTP 失败", file=sys.stderr)
        sys.exit(22)

def size_of(path):
    return os.path.getsize(path) if path else 0

bad = os.environ.get("STUB_CURL_BAD_SIZE") == "1"
parsed = urlsplit(url or "")
query = parse_qs(parsed.query)
if parsed.hostname == "gitee.com" and parsed.path.endswith("/releases"):
    payload = {"id": 987654321}
elif parsed.hostname == "gitee.com" and parsed.path.endswith("/attach_files"):
    payload = {"name": os.path.basename(upload_path or ""),
               "size": size_of(upload_path) - (1 if bad else 0)}
elif parsed.hostname == "api.github.com" and parsed.path.endswith("/releases"):
    payload = {"id": 555000111}
elif parsed.hostname == "uploads.github.com" and "/assets" in parsed.path:
    payload = {"name": (query.get("name") or [""])[0],
               "size": size_of(body_path) - (1 if bad else 0),
               "state": "uploaded"}
else:
    print(f"curl: (22) 桩未处理的地址 {url}", file=sys.stderr)
    sys.exit(22)

if out:
    Path(out).write_text(json.dumps(payload), encoding="utf-8")
if write_fmt:
    sys.stdout.write(write_fmt.replace("\\n", "\n").replace("%{http_code}", "201"))
sys.exit(0)
'''

ZIP_STUB = r'''#!/usr/bin/env python3
import os, sys, zipfile
from pathlib import Path

log_dir = Path(os.environ["STUB_LOG"])
log_dir.mkdir(parents=True, exist_ok=True)
args = sys.argv[1:]
with open(log_dir / "zip.log", "a", encoding="utf-8") as fh:
    fh.write(" ".join(args) + "\n")
positional = [a for a in args if not a.startswith("-")]
target = Path(positional[0])
base = Path(positional[1] if len(positional) > 1 else ".").resolve()
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(base.rglob("*")):
        if path.is_file():
            archive.write(path, path.relative_to(base).as_posix())
sys.exit(0)
'''

JAVA_STUB = '''#!/bin/sh
echo 'openjdk version "21.0.0" 桩' >&2
exit 0
'''

KEYTOOL_STUB = '''#!/bin/sh
echo '桩 keytool 不接触真实签名文件' >&2
exit 0
'''

DOCTOR_STUB = r'''#!/usr/bin/env python3
import os, sys
from pathlib import Path
log = Path(os.environ["STUB_LOG"]) / "doctor.log"
with open(log, "a", encoding="utf-8") as fh:
    fh.write(" ".join(sys.argv[1:]) + "\n")
print("OK 构建环境桩通过")
raise SystemExit(0)
'''

GRADLEW_STUB = '''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "${STUB_LOG}/gradlew.log"
mkdir -p androidApp/build/outputs/apk/debug
printf 'dummy-apk-bytes' > androidApp/build/outputs/apk/debug/androidApp-debug.apk
'''


def _write(path, text, mode=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    if mode is not None:
        path.chmod(mode)


def _snapshot(root):
    state = {}
    for path in sorted(root.rglob("*")):
        if path.is_file():
            state[path.relative_to(root).as_posix()] = (
                hashlib.sha256(path.read_bytes()).hexdigest(),
                stat.S_IMODE(path.stat().st_mode),
            )
    return state


def _strings(value):
    if isinstance(value, dict):
        for item in value.values():
            yield from _strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)
    elif isinstance(value, str):
        yield value


def _run(argv, **kwargs):
    kwargs.setdefault("capture_output", True)
    kwargs.setdefault("text", True)
    kwargs.setdefault("encoding", "utf-8")
    kwargs.setdefault("errors", "replace")
    return subprocess.run(argv, **kwargs)


class DoctorHandoffRegression(unittest.TestCase):
    def setUp(self):
        if not REAL_GIT:
            self.skipTest("系统 git 不可用")
        if not DOCTOR_SOURCE.is_file():
            self.skipTest("scripts/doctor.py 不存在")
        self.tmp = tempfile.TemporaryDirectory(prefix="portability ")
        self.root = Path(self.tmp.name)
        self.base = self.root / "工作区 带空格"
        self.project = self.base / "项目 XJ"
        self.feedback = self.base / "xj-feedback"
        self.home = self.root / "home"
        (self.home / "tmp").mkdir(parents=True)
        self.project.mkdir(parents=True)
        for name in ("scripts", "state", "docs"):
            (self.project / name).mkdir()
        shutil.copy2(DOCTOR_SOURCE, self.project / "scripts" / "doctor.py")
        _write(self.project / "state" / "workspace.json",
               json.dumps(WORKSPACE, ensure_ascii=False, indent=2) + "\n")
        _write(self.project / "state" / "bugs.json", '{"bugs": []}\n')
        _write(self.project / "AGENTS.md", "# 共享规则桩\n")
        _write(self.project / "CLAUDE.md", "@AGENTS.md\n")
        _write(self.project / "GEMINI.md", "@./AGENTS.md\n")
        _write(self.project / "docs" / "项目接手.md", "# 接手桩\n")
        self.init_repo(self.project, GITEE_URL)
        self.init_repo(self.feedback, FEEDBACK_ORIGIN)
        for name in ("AGENTS.md", "CLAUDE.md", "GEMINI.md"):
            _write(self.feedback / name, "# 反馈仓库入口桩\n")

    def tearDown(self):
        self.tmp.cleanup()

    def git_env(self):
        return {
            "PATH": BASE_PATH,
            "HOME": str(self.home),
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_SYSTEM": os.devnull,
            "GIT_TERMINAL_PROMPT": "0",
            "LANG": "C",
            "PYTHONUTF8": "1",
            "PYTHONIOENCODING": "utf-8",
        }

    def init_repo(self, path, origin):
        path.mkdir(parents=True, exist_ok=True)
        _run([REAL_GIT, "init", "-q", str(path)], env=self.git_env(), check=True)
        _run([REAL_GIT, "-C", str(path), "remote", "add", "origin", origin],
             env=self.git_env(), check=True)

    def run_doctor(self, extra=None, cwd=None):
        env = self.git_env()
        env.update(extra or {})
        return _run([sys.executable, str(self.project / "scripts" / "doctor.py")],
                    cwd=str(cwd or self.base), env=env, timeout=120)

    def test_default_handoff_passes_outside_project_with_spaces(self):
        outside = self.root / "外部 cwd"
        outside.mkdir()
        result = self.run_doctor(cwd=outside)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("OK source 独立仓库", result.stdout)
        self.assertIn("OK source origin 与登记仓库一致", result.stdout)
        self.assertIn("OK feedback 独立仓库", result.stdout)
        self.assertIn("OK 诊断仓库与源码仓库平级或位于独立目录", result.stdout)
        self.assertIn("OK feedback origin 与登记仓库一致", result.stdout)
        self.assertIn("OK feedback 接手入口 CLAUDE.md", result.stdout)
        self.assertIn("OK CLAUDE.md 引用共享规则", result.stdout)
        self.assertIn("OK GEMINI.md 引用共享规则", result.stdout)
        self.assertIn("OK 项目接手文件 docs/项目接手.md", result.stdout)
        self.assertNotIn("FAIL", result.stdout)
        self.assertNotIn("发布工具 curl", result.stdout)

    def test_feedback_dir_override_uses_alternate_repo(self):
        alternate = self.root / "反馈 仓库 单独"
        self.init_repo(alternate, FEEDBACK_ORIGIN)
        for name in ("AGENTS.md", "CLAUDE.md", "GEMINI.md"):
            _write(alternate / name, "# 反馈仓库入口桩\n")
        shutil.rmtree(self.feedback)
        result = self.run_doctor({"XJ_FEEDBACK_DIR": str(alternate)})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"OK feedback 独立仓库: {alternate.resolve()}", result.stdout)

    def test_missing_feedback_repo_rejected(self):
        shutil.rmtree(self.feedback)
        result = self.run_doctor()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAIL feedback 独立仓库", result.stdout)

    def test_relative_feedback_dir_resolves_against_source_root(self):
        env = self.git_env()
        env["XJ_FEEDBACK_DIR"] = "../xj-feedback"
        result = _run([sys.executable, str(self.project / "scripts" / "doctor.py")],
                      cwd="/tmp", env=env, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"OK feedback 独立仓库: {self.feedback.resolve()}", result.stdout)

    def test_nested_feedback_dir_rejected(self):
        nested = self.project / "内嵌 反馈"
        self.init_repo(nested, FEEDBACK_ORIGIN)
        result = self.run_doctor({"XJ_FEEDBACK_DIR": str(nested)})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAIL 诊断仓库与源码仓库平级或位于独立目录", result.stdout)

    def test_wrong_feedback_origin_rejected(self):
        _run([REAL_GIT, "-C", str(self.feedback), "remote", "set-url", "origin",
              "https://gitee.com/xiaomirezhale/not-feedback.git"],
             env=self.git_env(), check=True)
        result = self.run_doctor()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAIL feedback origin 与登记仓库一致", result.stdout)

    def test_claude_gemini_references_enforced(self):
        _write(self.project / "GEMINI.md", "@AGENTS.md\n")
        result = self.run_doctor()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAIL GEMINI.md 引用共享规则", result.stdout)
        self.assertIn("OK CLAUDE.md 引用共享规则", result.stdout)
        _write(self.project / "GEMINI.md", "@./AGENTS.md\n")
        _write(self.project / "CLAUDE.md", "见 AGENTS.md\n")
        result = self.run_doctor()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAIL CLAUDE.md 引用共享规则", result.stdout)
        self.assertIn("OK GEMINI.md 引用共享规则", result.stdout)

    def test_signing_manifest_carries_no_private_key(self):
        path = REPO_ROOT / "state" / "workspace.json"
        if not path.is_file():
            self.skipTest("state/workspace.json 不存在")
        raw = path.read_text(encoding="utf-8")
        self.assertNotIn("PRIVATE KEY", raw.upper())
        self.assertNotIn("-----BEGIN", raw)
        data = json.loads(raw)
        signing = data.get("android_signing")
        self.assertIsInstance(signing, dict)
        self.assertTrue(signing.get("keystore"))
        self.assertNotIn("\n", signing["keystore"])
        for value in _strings(data):
            self.assertNotIn("PRIVATE KEY", value.upper())
            self.assertNotIn("-----BEGIN", value)
            self.assertLess(len(value), 500)


class ReleaseFixture:
    def __init__(self, root):
        self.root = root
        self.base = root / "工作区 带空格"
        self.project = self.base / "项目 XJ"
        self.stub = self.base / "stub bin"
        self.home = self.base / "home"
        self.log = self.base / "桩记录"
        (self.home / "tmp").mkdir(parents=True)
        self.stub.mkdir()
        self.log.mkdir()
        self._build()
        self._init_git_repo()

    def _build(self):
        project = self.project
        for name in ("scripts", "state", "dist", "ToolboxApp", "engine"):
            (project / name).mkdir(parents=True)
        shutil.copy2(RELEASE_SOURCE, project / "scripts" / "release_module.sh")
        (project / "scripts" / "release_module.sh").chmod(0o755)
        _write(project / "scripts" / "doctor.py", DOCTOR_STUB, 0o755)
        _write(project / "ToolboxApp" / "gradlew", GRADLEW_STUB, 0o755)
        module = project / "dist" / "HyperOS_XJ_New"
        _write(module / "module.prop", MODULE_PROP)
        _write(module / "META-INF" / "com" / "google" / "android" / "update-binary",
               "#!/sbin/sh\nexit 0\n", 0o755)
        _write(project / "engine" / "tools" / "noop.sh", "#!/bin/sh\ntrue\n", 0o755)
        _write(project / "state" / "workspace.json",
               json.dumps(WORKSPACE, ensure_ascii=False, indent=2) + "\n")
        _write(project / "latest.json", json.dumps({
            "version": "3.1.66",
            "versionCode": 376,
            "date": "2026-11-18",
            "url": "",
            "notes": "上一版说明",
        }, ensure_ascii=False, indent=2) + "\n")
        _write(project / "state" / "release.json", json.dumps({
            "project": "桩项目",
            "history": [],
        }, ensure_ascii=False, indent=2) + "\n")
        _write(self.stub / "git", GIT_STUB, 0o755)
        _write(self.stub / "curl", CURL_STUB, 0o755)
        _write(self.stub / "zip", ZIP_STUB, 0o755)
        _write(self.stub / "java", JAVA_STUB, 0o755)
        _write(self.stub / "keytool", KEYTOOL_STUB, 0o755)

    def _init_git_repo(self):
        env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull,
               "GIT_CONFIG_SYSTEM": os.devnull, "GIT_TERMINAL_PROMPT": "0"}
        _run([REAL_GIT, "init", "-q", "-b", "master", str(self.project)],
             env=env, check=True)
        for key, value in (("user.name", "隔离回归"),
                           ("user.email", "regression@example.invalid")):
            _run([REAL_GIT, "-C", str(self.project), "config", key, value],
                 env=env, check=True)
        _run([REAL_GIT, "-C", str(self.project), "add", "-A"], env=env, check=True)
        _run([REAL_GIT, "-C", str(self.project), "commit", "-qm", "fixture"],
             env=env, check=True)

    def env(self, extra=None):
        values = {
            "PATH": f"{self.stub}:{BASE_PATH}",
            "HOME": str(self.home),
            "TMPDIR": str(self.home / "tmp"),
            "STUB_LOG": str(self.log),
            "STUB_REAL_GIT": REAL_GIT,
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_SYSTEM": os.devnull,
            "GIT_TERMINAL_PROMPT": "0",
            "PYTHONUTF8": "1",
            "PYTHONIOENCODING": "utf-8",
            "LANG": "C",
        }
        if extra:
            values.update(extra)
        return values

    def run(self, args, extra=None):
        return _run(["bash", str(self.project / "scripts" / "release_module.sh"), *args],
                    cwd=str(self.base), env=self.env(extra), timeout=300)

    def read_log(self, name):
        path = self.log / name
        if not path.is_file():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]

    def zip_path(self, version, local=False):
        name = f"热炸了工具箱_v{version}.zip" if local else f"rezhale_toolbox_v{version}.zip"
        return self.project / "dist" / name


class ReleaseCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="portability ")
        self.fx = ReleaseFixture(Path(self.tmp.name))
        env = self.fx.env()
        for tool in ("git", "curl", "zip", "java", "keytool"):
            self.assertEqual(shutil.which(tool, path=env["PATH"]), str(self.fx.stub / tool))

    def tearDown(self):
        self.tmp.cleanup()


class ReleaseBoundaryRegression(ReleaseCase):
    def test_help_and_dry_run_do_not_write_or_call_stubs(self):
        before = _snapshot(self.fx.project)
        result = self.fx.run(["--help"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("--online", result.stdout)
        self.assertIn("--check", result.stdout)
        self.assertIn("--dry-run", result.stdout)
        result = self.fx.run(["3.1.67", "--notes", "干跑说明", "--dry-run"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("干跑：将执行以下动作", result.stdout)
        self.assertIn("rezhale_toolbox_v3.1.67.zip", result.stdout)
        self.assertEqual(_snapshot(self.fx.project), before)
        self.assertEqual(list(self.fx.log.iterdir()), [])

    def test_check_profile_delegates_without_side_effects(self):
        before = _snapshot(self.fx.project)
        result = self.fx.run(["--check"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(_snapshot(self.fx.project), before)
        self.assertEqual(
            (self.fx.log / "doctor.log").read_text(encoding="utf-8").strip(),
            "--profile release")
        self.assertFalse((self.fx.log / "gradlew.log").exists())
        self.assertFalse((self.fx.log / "curl.log").exists())

    def test_notes_without_value_rejected(self):
        before = _snapshot(self.fx.project)
        result = self.fx.run(["3.1.67", "--notes"])
        self.assertEqual(result.returncode, 2)
        self.assertIn("--notes 缺少说明", result.stderr)
        self.assertEqual(_snapshot(self.fx.project), before)
        self.assertEqual(list(self.fx.log.iterdir()), [])

    def test_invalid_version_rejected(self):
        for bad in ("3.1", "v3.1.67", "3.1.67.1"):
            result = self.fx.run([bad, "--notes", "说明"])
            self.assertEqual(result.returncode, 2, bad)
            self.assertIn("版本号格式应为 X.Y.Z", result.stderr)
        result = self.fx.run(["3.1.67", "--notes", "说明", "--code", "abc"])
        self.assertEqual(result.returncode, 2)
        self.assertIn("versionCode 必须是整数", result.stderr)

    def test_local_rejects_other_version_and_code(self):
        for args in (["--local", "3.1.99"], ["--local", "3.1.66", "--code", "999"]):
            result = self.fx.run(args)
            self.assertEqual(result.returncode, 2, args)
            self.assertIn("--local 只打包当前版本", result.stderr)

    def test_publish_rejects_non_master_branch_before_build(self):
        env = self.fx.env()
        _run([REAL_GIT, "-C", str(self.fx.project), "checkout", "-qb", "topic"],
             env=env, check=True)
        result = self.fx.run(["3.1.67"], {"GITEE_TOKEN": "stub", "GITHUB_TOKEN": "stub"})
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("只允许在 master 分支", result.stderr)
        self.assertFalse((self.fx.log / "gradlew.log").exists())
        self.assertFalse((self.fx.log / "curl.log").exists())

    def test_publish_rejects_detached_head_before_build(self):
        env = self.fx.env()
        _run([REAL_GIT, "-C", str(self.fx.project), "checkout", "--detach", "HEAD"],
             env=env, check=True)
        result = self.fx.run(["3.1.67"], {"GITEE_TOKEN": "stub", "GITHUB_TOKEN": "stub"})
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("拒绝 detached HEAD", result.stderr)
        self.assertFalse((self.fx.log / "gradlew.log").exists())
        self.assertFalse((self.fx.log / "curl.log").exists())

    def test_publish_rejects_untracked_release_source_before_build(self):
        engine_file = self.fx.project / "engine" / "lib" / "new_process.sh"
        engine_file.parent.mkdir(parents=True, exist_ok=True)
        engine_file.write_text("#!/bin/sh\\ntrue\\n", encoding="utf-8")
        result = self.fx.run(["3.1.67"], {"GITEE_TOKEN": "stub", "GITHUB_TOKEN": "stub"})
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("拒绝发行源码目录中的未跟踪文件", result.stderr)
        self.assertIn("engine/lib/new_process.sh", result.stderr)
        self.assertFalse((self.fx.log / "gradlew.log").exists())
        self.assertFalse((self.fx.log / "curl.log").exists())

    def test_shell_syntax_check_rejects_bad_second_file(self):
        with tempfile.TemporaryDirectory(prefix="syntax batch ") as tmp:
            root = Path(tmp)
            _write(root / "a-good.sh", "#!/bin/sh\\ntrue\\n")
            _write(root / "z-bad.sh", "if then\\n")
            result = _run([
                "find", str(root), "-type", "f", "-name", "*.sh", "-exec", "sh", "-c",
                'for file do sh -n "$file" || exit; done', "sh", "{}", "+",
            ])
            self.assertNotEqual(result.returncode, 0)

    def test_publish_does_not_stage_unrelated_untracked_files(self):
        marker = self.fx.project / "unrelated.txt"
        marker.write_text("untracked content\\n", encoding="utf-8")
        result = self.fx.run(["3.1.67"], {"GITEE_TOKEN": "stub", "GITHUB_TOKEN": "stub"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        env = self.fx.env()
        committed = _run([REAL_GIT, "-C", str(self.fx.project), "diff-tree", "--no-commit-id",
                          "--name-only", "-r", "HEAD"], env=env, check=True).stdout.splitlines()
        self.assertNotIn("unrelated.txt", committed)
        self.assertTrue(marker.is_file())
        status = _run([REAL_GIT, "-C", str(self.fx.project), "status", "--short"],
                      env=env, check=True).stdout
        self.assertIn("?? unrelated.txt", status)

    def test_publish_rejects_existing_index_without_changing_it(self):
        env = self.fx.env()
        marker = self.fx.project / "unrelated.txt"
        marker.write_text("staged content\\n", encoding="utf-8")
        _run([REAL_GIT, "-C", str(self.fx.project), "add", "--", "unrelated.txt"],
             env=env, check=True)
        before = _run([REAL_GIT, "-C", str(self.fx.project), "diff", "--cached", "--binary"],
                      env=env, check=True).stdout
        result = self.fx.run(["3.1.67"], {"GITEE_TOKEN": "stub", "GITHUB_TOKEN": "stub"})
        after = _run([REAL_GIT, "-C", str(self.fx.project), "diff", "--cached", "--binary"],
                     env=env, check=True).stdout
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("拒绝已有暂存改动", result.stderr)
        self.assertEqual(after, before)
        self.assertFalse((self.fx.log / "gradlew.log").exists())
        self.assertFalse((self.fx.log / "curl.log").exists())

    def test_local_builds_current_version_offline_only(self):
        result = self.fx.run(["--local"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("完成（--local）", result.stdout)
        zip_path = self.fx.zip_path("3.1.66", local=True)
        self.assertTrue(zip_path.is_file())
        with zipfile.ZipFile(zip_path) as archive:
            props = dict(line.split("=", 1) for line in
                         archive.read("module.prop").decode("utf-8").splitlines() if "=" in line)
            self.assertEqual((props["version"], props["versionCode"]), ("3.1.66", "376"))
        self.assertFalse(self.fx.zip_path("3.1.67", local=True).exists())
        self.assertFalse((self.fx.log / "curl.log").exists())
        pushes = [entry for entry in self.fx.read_log("git.log") if "push" in entry]
        self.assertEqual(pushes, [])
        gradle = (self.fx.log / "gradlew.log").read_text(encoding="utf-8")
        self.assertIn("--offline", gradle)
        doctor_calls = (self.fx.log / "doctor.log").read_text(encoding="utf-8").splitlines()
        self.assertGreaterEqual(len(doctor_calls), 1)
        self.assertTrue(all(call == "--profile build" for call in doctor_calls))


class ReleasePipelineSimulation(ReleaseCase):
    def assert_full_publish(self, version="3.1.67", code="377"):
        zip_path = self.fx.zip_path(version)
        self.assertTrue(zip_path.is_file())
        with zipfile.ZipFile(zip_path) as archive:
            props = dict(line.split("=", 1) for line in
                         archive.read("module.prop").decode("utf-8").splitlines() if "=" in line)
            self.assertEqual((props["version"], props["versionCode"]), (version, code))
            info = archive.getinfo("META-INF/com/google/android/update-binary")
            self.assertEqual(stat.S_IMODE(info.external_attr >> 16), 0o755)
            self.assertEqual(archive.read("app/rezhale_toolbox.apk"), b"dummy-apk-bytes")
        latest = json.loads((self.fx.project / "latest.json").read_text(encoding="utf-8"))
        self.assertEqual(latest["version"], version)
        self.assertEqual(latest["versionCode"], int(code))
        self.assertEqual(
            latest["download"],
            f"https://github.com/{GITHUB_REPO}/releases/download/v{version}/rezhale_toolbox_v{version}.zip")
        self.assertEqual(
            latest["download_gitee"],
            f"https://gitee.com/{GITEE_REPO}/releases/download/v{version}/rezhale_toolbox_v{version}.zip")
        release = json.loads((self.fx.project / "state" / "release.json").read_text(encoding="utf-8"))
        self.assertEqual(release["history"][0]["version"], f"{version}（{code}，已发布）")
        links = (self.fx.project / "dist" / f"下载链接_v{version}.txt").read_text(encoding="utf-8")
        self.assertIn(f"https://gitee.com/{GITEE_REPO}/releases/download/v{version}/rezhale_toolbox_v{version}.zip", links)
        self.assertIn(f"https://github.com/{GITHUB_REPO}/releases/download/v{version}/rezhale_toolbox_v{version}.zip", links)
        gradle = (self.fx.log / "gradlew.log").read_text(encoding="utf-8")
        self.assertIn(":androidApp:assembleDebug", gradle)
        doctor_calls = (self.fx.log / "doctor.log").read_text(encoding="utf-8").splitlines()
        self.assertGreaterEqual(len(doctor_calls), 1)
        self.assertTrue(all(call == "--profile build" for call in doctor_calls))
        calls = self.fx.read_log("curl.log")
        self.assertEqual([call["url"] for call in calls], [
            f"https://gitee.com/api/v5/repos/{GITEE_REPO}/releases",
            f"https://gitee.com/api/v5/repos/{GITEE_REPO}/releases/987654321/attach_files",
            f"https://api.github.com/repos/{GITHUB_REPO}/releases",
            f"https://uploads.github.com/repos/{GITHUB_REPO}/releases/555000111/assets?name=rezhale_toolbox_v{version}.zip",
        ])
        self.assertEqual(calls[1]["upload"], str(zip_path))
        self.assertEqual(calls[3]["body"], str(zip_path))
        hosts = {urlsplit(call["url"]).hostname for call in calls}
        self.assertEqual(hosts, {"gitee.com", "api.github.com", "uploads.github.com"})
        self.assertNotIn("xj-feedback", " ".join(call["url"] for call in calls))
        pushes = [entry[entry.index("push"):] for entry in self.fx.read_log("git.log")
                  if "push" in entry]
        self.assertEqual(pushes, [
            ["push", GITEE_URL, "master"],
            ["push", GITEE_URL, f"v{version}"],
            ["push", GITHUB_URL, "master"],
            ["push", GITHUB_URL, f"v{version}"],
        ])
        self.assertFalse(any("xj-feedback" in " ".join(entry) for entry in pushes))
        push_calls = self.fx.read_log("push.log")
        self.assertEqual(len(push_calls), 4)
        self.assertEqual({call["host"] for call in push_calls}, {"gitee.com", "github.com"})
        for call in push_calls:
            self.assertFalse(call["url_has_at"], call["host"])
            self.assertTrue(call["credential_helper_cleared"], call["host"])
            verify = call.get("askpass")
            self.assertIsNotNone(verify, f"{call['host']} 推送未使用 GIT_ASKPASS")
            self.assertIn(verify["require"], ("", "force"))
            self.assertEqual(verify["terminal_prompt"], "0")
            self.assertFalse(verify["contains_token"], f"{call['host']} 临时脚本含令牌")
            self.assertTrue(verify["username_ok"], call["host"])
            self.assertTrue(verify["password_ok"], call["host"])
            self.assertTrue(verify["password_from_env"], call["host"])
            self.assertFalse(Path(verify["script"]).exists(), "认证脚本退出后未清理")
        push_log = (self.fx.log / "push.log").read_text(encoding="utf-8")
        for secret in ("stub-gitee-token", "stub-github-token",
                       "placeholder-gitee", "placeholder-github"):
            self.assertNotIn(secret, push_log)
        return calls

    def test_full_publish_uses_credential_helper_and_both_hosts(self):
        result = self.fx.run(["3.1.67", "--notes", "迁移回归说明"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("包校验通过", result.stdout)
        self.assertIn("OK 构建环境桩通过", result.stdout)
        self.assertIn("链接文件（纯地址", result.stdout)
        self.assertIn("gitee release http=201", result.stdout)
        self.assertTrue(re.search(r"gitee asset\s+http=201", result.stdout))
        self.assertIn("github release http=201", result.stdout)
        self.assertTrue(re.search(r"github asset\s+http=201", result.stdout))
        self.assertTrue((self.fx.log / "credential.log").is_file())
        self.assertEqual(
            (self.fx.log / "credential.log").read_text(encoding="utf-8").split(),
            ["gitee.com", "github.com"])
        calls = self.assert_full_publish()
        self.assertIn("Authorization: token stub-gitee-token", calls[0]["headers"])
        self.assertIn("Authorization: Bearer stub-github-token", calls[2]["headers"])

    def test_full_publish_uses_env_tokens_without_helper(self):
        result = self.fx.run(["3.1.67", "--notes", "环境令牌桩"], {
            "GITEE_TOKEN": "placeholder-gitee",
            "GITHUB_TOKEN": "placeholder-github",
        })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.fx.log / "credential.log").exists())
        calls = self.assert_full_publish()
        self.assertIn("Authorization: token placeholder-gitee", calls[0]["headers"])
        self.assertIn("Authorization: Bearer placeholder-github", calls[2]["headers"])

    def test_online_build_omits_offline_flag(self):
        result = self.fx.run(["3.1.67", "--notes", "在线依赖桩", "--online"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        gradle = (self.fx.log / "gradlew.log").read_text(encoding="utf-8")
        self.assertNotIn("--offline", gradle)
        self.assertIn(":androidApp:assembleDebug", gradle)
        self.assertIn("--console=plain", gradle)

    def test_gitee_repo_override_to_feedback_rejected(self):
        result = self.fx.run(["3.1.67", "--notes", "说明"], {"GITEE_REPO": FEEDBACK_REPO})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("发行目标与源码仓库清单不符", result.stdout + result.stderr)
        self.assertFalse((self.fx.log / "gradlew.log").exists())
        self.assertFalse((self.fx.log / "curl.log").exists())

    def test_curl_failure_exits_nonzero(self):
        result = self.fx.run(["3.1.67", "--notes", "说明"], {
            "STUB_CURL_FAIL": "gitee.com/api/v5/repos",
        })
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("桩注入的 HTTP 失败", result.stderr)
        self.assertNotIn("链接文件（纯地址", result.stdout)

    def test_upload_size_mismatch_exits_nonzero(self):
        result = self.fx.run(["3.1.67", "--notes", "说明"], {"STUB_CURL_BAD_SIZE": "1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Gitee 资产校验失败", result.stderr)
        self.assertNotIn("链接文件（纯地址", result.stdout)

    def test_push_askpass_accepts_both_credential_sources(self):
        sources = (
            ({}, True),
            ({"GITEE_TOKEN": "placeholder-gitee", "GITHUB_TOKEN": "placeholder-github"}, False),
        )
        for extra, uses_helper in sources:
            with tempfile.TemporaryDirectory(prefix="portability askpass ") as tmp:
                fx = ReleaseFixture(Path(tmp))
                result = fx.run(["3.1.67", "--notes", "askpass 凭据来源"], extra)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual((fx.log / "credential.log").exists(), uses_helper)
                calls = fx.read_log("push.log")
                self.assertEqual(len(calls), 4)
                for call in calls:
                    verify = call.get("askpass")
                    self.assertIsNotNone(verify, call["host"])
                    self.assertEqual(verify["terminal_prompt"], "0")
                    self.assertTrue(verify["username_ok"], call["host"])
                    self.assertTrue(verify["password_ok"], call["host"])
                    self.assertFalse(verify["contains_token"], call["host"])
                    self.assertFalse(Path(verify["script"]).exists(), "认证脚本退出后未清理")

    def test_push_failure_exits_nonzero(self):
        result = self.fx.run(["3.1.67", "--notes", "说明"], {
            "STUB_GIT_PUSH_FAIL": "gitee.com",
        })
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("推送被拒", result.stderr)
        self.assertFalse((self.fx.log / "curl.log").exists())


if __name__ == "__main__":
    program = unittest.main(exit=False, verbosity=2)
    result = program.result
    total = result.testsRun
    if result.wasSuccessful():
        print(f"\n跨目录与离线发布隔离回归通过：{total} 项")
        sys.exit(0)
    failed = len(result.failures) + len(result.errors) + len(result.unexpectedSuccesses)
    print(f"\n隔离回归未通过：{failed} 项异常，共 {total} 项", file=sys.stderr)
    sys.exit(1)