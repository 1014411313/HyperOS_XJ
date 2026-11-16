#!/usr/bin/env python3
"""数据源读/冷却策略的真实 JVM 行为测试。

用 gradle 缓存里已有的 kotlin-compiler-embeddable + kotlin-stdlib + 本机 JDK 编译
`StatusSourceCache.kt`（androidMain，纯 JDK）和 `StatusSourceBehaviorTest.kt` 后直接运行，
不跑 gradle（避免并发锁）、不装任何全局工具、不加依赖。

找不到编译器缓存时报 SKIP 并返回 2（如实说明无法动态验证），不伪造通过。

用法：python3 scripts/status_source_test.py
"""

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
SOURCE = ROOT / "ToolboxApp/app/src/androidMain/kotlin/com/hyperos/xj/runtime/StatusSourceCache.kt"
TEST = ROOT / "scripts/StatusSourceBehaviorTest.kt"
MAIN_CLASS = "com.hyperos.xj.runtime.StatusSourceBehaviorTestKt"


def newest_jar(group, artifact):
    base = CACHE / group / artifact
    if not base.is_dir():
        return None
    jars = sorted(base.glob("*/*/*.jar"), key=lambda p: p.stat().st_mtime, reverse=True)
    return jars[0] if jars else None


def compiler_classpath(jars):
    return ":".join(str(j) for j in jars if j)


def main():
    compiler = newest_jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
    stdlib = newest_jar("org.jetbrains.kotlin", "kotlin-stdlib")
    reflect = newest_jar("org.jetbrains.kotlin", "kotlin-reflect")
    coroutines = newest_jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
    annotations = newest_jar("org.jetbrains", "annotations")
    java = shutil.which("java")
    if not compiler or not stdlib or not reflect or not coroutines or not annotations or not java:
        print("SKIP 找不到 kotlin 编译器所需的缓存 jar/java，无法动态验证")
        print("     compiler=%s stdlib=%s reflect=%s coroutines=%s annotations=%s java=%s"
              % (compiler, stdlib, reflect, coroutines, annotations, java))
        return 2

    out = Path(tempfile.mkdtemp(prefix="xj-status-test-"))
    try:
        compile_cmd = [
            java, "-cp", compiler_classpath([compiler, stdlib, reflect, coroutines, annotations]),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect",
            "-classpath", str(stdlib),
            "-d", str(out),
            str(SOURCE), str(TEST),
        ]
        compiled = subprocess.run(compile_cmd, capture_output=True, text=True)
        if compiled.returncode != 0:
            print("FAIL 编译失败")
            print(compiled.stdout)
            print(compiled.stderr)
            return 1
        run_cmd = [java, "-cp", "%s:%s" % (out, stdlib), MAIN_CLASS]
        ran = subprocess.run(run_cmd, capture_output=True, text=True)
        print(ran.stdout.strip())
        if ran.stderr.strip():
            print(ran.stderr.strip())
        return 0 if ran.returncode == 0 else 1
    finally:
        shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
