#!/usr/bin/env python3
"""JSON settings replacement regression for multiple sequential updates."""

import json
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix="xj-config-write-") as raw:
        root = Path(raw)
        conf = root / "config"
        state = root / "state"
        conf.mkdir()
        state.mkdir()
        settings = conf / "settings.json"
        count = 32
        settings.write_text(json.dumps({f"key_{i}": "old" for i in range(count)}, indent=2) + "\n")
        script = f'''
export XJ_READONLY=1
. "{ROOT / 'engine/lib/env.sh'}"
XJ_READONLY=0
CONFDIR="{conf}"
STATE="{state}"
BT_STATE="{state}"
BT_CONF="{root / 'battery.conf'}"
for i in $(seq 0 {count - 1}); do
  json_set "key_$i" "value_$i" str || exit 1
done
'''
        result = subprocess.run(["sh", "-c", script], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(f"rc={result.returncode}\n{result.stdout}{result.stderr}")
        data = json.loads(settings.read_text())
        expected = {f"key_{i}": f"value_{i}" for i in range(count)}
        if data != expected:
            raise AssertionError(f"successive writes were lost: {data}")
        if list(conf.glob("settings.json.new.*")):
            raise AssertionError("temporary file left behind")
    print(f"config write regression: PASS ({count} sequential updates retained, JSON valid)")


if __name__ == "__main__":
    main()
