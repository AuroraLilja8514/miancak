#!/usr/bin/env python3
"""Reproduce a pinned public APK failure and retain only its own ANR traces."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]

def adb(*args):
    try:
        return subprocess.run(["adb", *args], capture_output=True,
                              timeout=20).stdout.decode("utf-8", errors="replace")
    except subprocess.TimeoutExpired:
        return ""

def collect():
    output = ROOT / "test-results/release"
    output.mkdir(parents=True, exist_ok=True)
    package = "org.miancak.keyboard"
    process_rows = adb("shell", "ps").splitlines()
    pids = set()
    for row in process_rows:
        parts = row.split()
        if len(parts) > 2 and parts[-1] == package and parts[1].isdigit():
            pids.add(parts[1])
    print("Diagnostic UID: " + adb("shell", "id").strip(), flush=True)
    print("Diagnostic own PIDs: " + ",".join(sorted(pids)), flush=True)
    print("Diagnostic ANR directory: " + adb("shell", "ls", "-l", "/data/anr").strip(), flush=True)
    for pid in sorted(pids):
        # Snapshot a hung process even when accessibility timeouts have not
        # caused Android's input-dispatch ANR monitor to write its trace.
        adb("shell", "kill", "-3", pid)
        native = adb("shell", "debuggerd", "-b", pid)
        (output / ("miancak-native-" + pid + ".txt")).write_text(native[:200000])
        kernel = adb("exec-out", "cat", "/proc/" + pid + "/stack")
        (output / ("miancak-kernel-" + pid + ".txt")).write_text(kernel)
        print("MIANCAK_KERNEL_BEGIN\n" + kernel + "\nMIANCAK_KERNEL_END", flush=True)
    time.sleep(1)
    paths = adb("shell", "find", "/data/anr", "-type", "f").splitlines()
    configured = adb("shell", "getprop", "dalvik.vm.stack-trace-file").strip()
    if configured.startswith("/data/"):
        paths.append(configured)
    paths.append("/data/anr/traces.txt")
    own = []
    for path in sorted(set(paths))[:20]:
        if not re.fullmatch(r"/data/anr/[A-Za-z0-9_.-]+", path.strip()):
            continue
        value = adb("exec-out", "cat", path.strip())
        blocks = re.split(r"(?=^----- pid )", value, flags=re.M)
        own.extend(block[:200000] for block in blocks
                   if re.search(r"^Cmd line: " + re.escape(package) + r"\s*$", block, re.M))
    (output / "miancak-anr.txt").write_text("\n".join(own))
    if own:
        print("MIANCAK_ANR_BEGIN\n" + "\n".join(own)[:200000] + "\nMIANCAK_ANR_END", flush=True)
    logs = adb("logcat", "-d", "-v", "threadtime")
    selected = []
    for line in logs.splitlines():
        parts = line.split()
        if (len(parts) > 3 and parts[2] in pids) or (
                package in line and re.search(r"ANR|am_anr|Input dispatching", line)):
            selected.append(line)
    (output / "miancak-logcat.txt").write_text("\n".join(selected)[-250000:])
    print("Diagnostic: own-process ANR blocks=" + str(len(own)), flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", type=int, required=True)
    args = parser.parse_args()
    result = 1
    try:
        result = subprocess.run([sys.executable, "tools/release/rehearsal.py", "device",
                                 "--folder", "rehearsal", "--api", str(args.api),
                                 "--timeout-seconds", "1000"], cwd=ROOT, timeout=1100).returncode
    finally:
        collect()
    raise SystemExit(result)

if __name__ == "__main__":
    sys.path.insert(0, str(ROOT / "scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    main()
