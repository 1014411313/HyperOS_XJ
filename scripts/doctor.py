#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit


def run(args, cwd=None):
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=30)


def check(root, profile):
    failures = 0

    def report(ok, text, required=True):
        nonlocal failures
        print(f"{'OK' if ok else 'FAIL' if required else 'WARN'} {text}")
        if required and not ok:
            failures += 1

    config = json.loads((root / 'state/workspace.json').read_text(encoding='utf-8'))
    print(f'系统: {platform.system()} / {platform.machine()}；项目: {root}')
    git = shutil.which('git')
    report(bool(git), 'Git 可用')
    if not git:
        return 1
    for role, spec in config['repositories'].items():
        override = os.environ.get(spec.get('directory_env', ''), '')
        configured = Path(override).expanduser() if override else Path(spec['directory'])
        path = (configured if configured.is_absolute() else root / configured).resolve()
        if role == 'feedback':
            separate = path != root and root not in path.parents and path not in root.parents
            report(separate, '诊断仓库与源码仓库平级或位于独立目录')
            if not separate:
                continue
        result = run([git, '-C', str(path), 'rev-parse', '--show-toplevel'])
        valid = result.returncode == 0 and Path(result.stdout.strip()).resolve() == path
        report(valid, f'{role} 独立仓库: {path}')
        if not valid:
            if 'dubious ownership' in result.stderr.lower():
                print('WARN Git 检测到属主不一致；核对目录归属，不自动全局放行 safe.directory')
            elif result.returncode and path.is_dir():
                print('WARN Git 无法识别该目录，请核对仓库根、目录访问权限和 Git 配置')
            continue
        remote = run([git, '-C', str(path), 'remote', 'get-url', 'origin'])
        raw_url = remote.stdout.strip()
        parsed = urlsplit(raw_url)
        identity = (parsed.hostname or '') + parsed.path if parsed.scheme else raw_url.replace('git@', '').replace(':', '/', 1)
        expected = urlsplit(spec['origin'])
        report(remote.returncode == 0 and identity.removesuffix('.git') == (expected.hostname + expected.path).removesuffix('.git'),
               f'{role} origin 与登记仓库一致')
        if role == 'feedback':
            for name in ('AGENTS.md', 'CLAUDE.md', 'GEMINI.md'):
                report((path / name).is_file(), f'feedback 接手入口 {name}')
    for name in ('AGENTS.md', 'CLAUDE.md', 'GEMINI.md', 'docs/项目接手.md', 'state/bugs.json'):
        report((root / name).is_file(), f'项目接手文件 {name}')
    for name, expected in (('CLAUDE.md', '@AGENTS.md'), ('GEMINI.md', '@./AGENTS.md')):
        path = root / name
        report(path.is_file() and path.read_text(encoding='utf-8').strip() == expected, f'{name} 引用共享规则')
    if profile == 'handoff':
        return int(failures > 0)

    for tool in ('bash', 'zip', 'java', 'keytool'):
        report(bool(shutil.which(tool)), f'构建工具 {tool}')
    app = root / 'ToolboxApp'
    java = shutil.which('java')
    if java:
        result = run([java, '-version'])
        match = re.search(r'version "(\d+)', result.stderr + result.stdout)
        report(result.returncode == 0 and bool(match) and int(match[1]) >= 21, 'JDK 21 或更高版本')
    sdk_value = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    local = app / 'local.properties'
    if not sdk_value and local.is_file():
        match = re.search(r'^sdk.dir=(.+)$', local.read_text(encoding='utf-8'), re.M)
        if match:
            sdk_value = match[1].replace('\\:', ':').replace('\\\\', '\\')
    sdk = Path(sdk_value).expanduser() if sdk_value else None
    if sdk and not sdk.is_absolute():
        sdk = (app / sdk).resolve()
    report(bool(sdk and sdk.is_dir()), 'Android SDK 已通过环境变量或 local.properties 配置')
    gradle_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    global_props = gradle_home / 'gradle.properties'
    if global_props.is_file():
        match = re.search(r'^android.aapt2FromMavenOverride=(.+)$', global_props.read_text(encoding='utf-8'), re.M)
        if match:
            override = Path(match[1]).expanduser()
            report(override.is_file(), '全局 AAPT2 覆盖路径存在')
            if override.is_file():
                try:
                    result = run([str(override), 'version'])
                    compatible = result.returncode == 0
                except OSError:
                    compatible = False
                report(compatible, '全局 AAPT2 与本机系统/架构兼容')
    report((gradle_home / 'caches').is_dir(), 'Gradle 缓存存在；首次构建用 --online 下载依赖', required=False)
    if sdk and sdk.is_dir():
        platforms = list((sdk / 'platforms').glob('*/source.properties'))
        platform37 = any(re.search(r'^AndroidVersion.ApiLevel=37(?:\.0)?$', p.read_text(encoding='utf-8'), re.M)
                         and (p.parent / 'android.jar').is_file() for p in platforms)
        report(platform37, 'Android 平台 37（按 SDK 元数据识别）')
        tools = sdk / 'build-tools/37.0.0'
        report(tools.is_dir(), 'Android Build Tools 37.0.0')
        jar = tools / 'lib/apksigner.jar'
        report(jar.is_file(), 'APK 签名校验工具')
        apk = app / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk'
        if apk.is_file() and jar.is_file() and java:
            result = run([java, '-jar', str(jar), 'verify', '--print-certs', str(apk)])
            match = re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
            report(result.returncode == 0 and bool(match) and match[1].lower() == config['android_signing']['certificate_sha256'],
                   '现有构建 APK 签名与已发布版本一致')
    key = Path(config['android_signing']['keystore']).expanduser()
    report(key.is_file(), '原 APK 签名文件已恢复（不能自动生成替代）')
    keytool = shutil.which('keytool')
    if key.is_file() and keytool:
        result = run([keytool, '-exportcert', '-alias', 'androiddebugkey', '-keystore', str(key), '-storepass', 'android', '-rfc'])
        match = re.search(r'-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----', result.stdout, re.S)
        digest = ''
        if result.returncode == 0 and match:
            import base64
            digest = hashlib.sha256(base64.b64decode(match[1])).hexdigest()
        report(digest == config['android_signing']['certificate_sha256'], '签名文件证书与已发布版本一致')
    if profile == 'build':
        return int(failures > 0)

    report(bool(shutil.which('curl')), '发布工具 curl')
    report(platform.system() != 'Windows', '发布脚本使用 Bash；Windows 请在 WSL 中运行')
    for host, variable, url in (('Gitee', 'GITEE_TOKEN', 'https://gitee.com'),
                                ('GitHub', 'GITHUB_TOKEN', 'https://github.com')):
        helper = run([git, 'config', '--get-urlmatch', 'credential.helper', url], cwd=root)
        has_helper = helper.returncode == 0 and bool(helper.stdout.strip())
        report(bool(os.environ.get(variable)) or has_helper, f'{host} 发布凭据来源已配置（未验证令牌或助手内是否有凭据）')
        if has_helper and not os.environ.get(variable):
            print(f'WARN {host} 仅检测到助手配置；实际发布前按主机取凭据，失败即停止')
    return int(failures > 0)


def main():
    parser = argparse.ArgumentParser(description='双仓库接手、构建和发布环境只读检查；不联网、不安装、不打印凭据。')
    parser.add_argument('--profile', choices=('handoff', 'build', 'release'), default='handoff')
    args = parser.parse_args()
    try:
        return check(Path(__file__).resolve().parents[1], args.profile)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'FAIL 检查无法完成: {type(error).__name__}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
