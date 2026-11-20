#!/usr/bin/env python3
import importlib.util
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("status_runner", ROOT / "scripts/status_source_test.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
runner.TEST = Path(__file__).with_name("AppStatusBehaviorTest.kt")
runner.MAIN_CLASS = "com.hyperos.xj.runtime.AppStatusBehaviorTestKt"
policy = ROOT / "ToolboxApp/app/src/commonMain/kotlin/com/hyperos/xj/runtime/StatusRefreshPolicy.kt"
source = runner.SOURCE.read_text() + "\n" + policy.read_text().split("\n", 1)[1]
with tempfile.TemporaryDirectory(prefix="xj-app-status-") as directory:
    runner.SOURCE = Path(directory) / "StatusPolicy.kt"
    runner.SOURCE.write_text(source)
    raise SystemExit(runner.main())
