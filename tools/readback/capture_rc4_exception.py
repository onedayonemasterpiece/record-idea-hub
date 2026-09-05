#!/usr/bin/env python3
"""Observe one caught RC4 exception through local ADB/JDWP without installing or changing app data."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=r'C:\platform-tools\adb.exe')
    parser.add_argument('--serial', default='R5CNC1DQ23H')
    parser.add_argument('--package', default='com.onedayonemasterpiece.recordideahub.v11')
    parser.add_argument('--seconds', type=int, default=90, choices=range(1, 121), metavar='1..120')
    parser.add_argument('--output', type=Path, default=Path('rc4-readback-exception.json'))
    args = parser.parse_args()
    port = None

    def adb(*command: str) -> str:
        result = subprocess.run([args.adb, '-s', args.serial, *command],
                                capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise RuntimeError('ADB command failed; output intentionally not copied')
        return result.stdout.strip()

    try:
        pids = adb('shell', 'pidof', args.package).split()
        if len(pids) != 1 or not pids[0].isdigit():
            raise RuntimeError('Exactly one running application PID is required; do not force-stop it')
        adb('shell', 'run-as', args.package, 'true')
        source = Path(__file__).with_name('ReadbackExceptionProbe.java')
        with tempfile.TemporaryDirectory(prefix='rc4-readback-') as classes:
            compile_result = subprocess.run(['javac', '--add-modules', 'jdk.jdi', '-d', classes, str(source)],
                                            capture_output=True, text=True, timeout=30)
            if compile_result.returncode:
                raise RuntimeError('Probe compilation failed; JDK 17+ with jdk.jdi is required')
            port = adb('forward', 'tcp:0', f'jdwp:{pids[0]}')
            if not port.isdigit():
                raise RuntimeError('ADB did not return a numeric local forwarding port')
            result = subprocess.run(['java', '--add-modules', 'jdk.jdi', '-cp', classes,
                                     'ReadbackExceptionProbe', port, str(args.seconds)],
                                    capture_output=True, text=True, timeout=args.seconds + 20)
            if result.returncode:
                raise RuntimeError('JDWP attach/probe failed; no raw debugger output copied')
            payload = json.loads(result.stdout.strip())
            args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print(json.dumps(payload, ensure_ascii=False))
            return 0 if payload.get('result') == 'captured' else 2
    except (OSError, RuntimeError, subprocess.TimeoutExpired, ValueError) as error:
        print(json.dumps({'result': 'probe_failed', 'error_type': type(error).__name__}))
        return 2
    finally:
        if port and port.isdigit():
            try:
                adb('forward', '--remove', f'tcp:{port}')
            except (OSError, RuntimeError, subprocess.TimeoutExpired):
                pass


if __name__ == '__main__':
    raise SystemExit(main())
