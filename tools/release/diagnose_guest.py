#!/usr/bin/env python3
"""Reproduce a pinned public APK failure and retain only its own ANR traces."""
import argparse
from collections import deque
from pathlib import Path
import re
import subprocess
import sys
import threading
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
    # Preserve bounded system evidence from this synthetic emulator. The
    # visible app can lose its accessibility root without an app ANR block.
    system_component = re.compile(
        r"UiAutomation|UiAutomator|Accessibility|InputDispatcher|WindowManager", re.I)
    system_signal = re.compile(
        r"miancak|timed? ?out|unresponsive|not responding|fail|died|dead|focus|accessib", re.I)
    system_lines = [line for line in logs.splitlines()
                    if system_component.search(line) and system_signal.search(line)]
    (output / "system-accessibility-logcat.txt").write_text("\n".join(system_lines)[-100000:])
    windows = adb("shell", "dumpsys", "window", "windows")
    focus_lines = [line for line in windows.splitlines()
                   if "org.miancak.keyboard" in line or
                   re.search(r"mCurrentFocus|mFocusedApp|mInputMethodTarget", line)]
    (output / "miancak-window-focus.txt").write_text("\n".join(focus_lines)[-30000:])
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
    # device_smoke also requires root. Restart adbd before attaching the live
    # stream so its own subsequent adb root does not disconnect the stream.
    subprocess.run(["adb", "root"], check=True, timeout=30,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["adb", "wait-for-device"], check=True, timeout=60,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # Keep a bounded stream on the host while the guest is alive: a fully
    # stalled guest cannot provide post-failure logcat or a Java stack.
    lines = deque(maxlen=3000)
    component = re.compile(r"UiAutomation|UiAutomator|Accessibility|InputDispatcher|WindowManager|ActivityManager|AndroidRuntime", re.I)
    signal = re.compile(r"miancak|timed? ?out|unresponsive|not responding|fail|died|dead|focus|accessib|ANR", re.I)
    stream = subprocess.Popen(["adb", "logcat", "-v", "threadtime"],
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                              text=True, errors="replace", bufsize=1)
    def read_stream():
        for line in stream.stdout:
            if "org.miancak.keyboard" in line or (component.search(line) and signal.search(line)):
                lines.append(line[-2000:])
    reader = threading.Thread(target=read_stream, daemon=True)
    reader.start()
    health_stop = threading.Event()
    health = deque(maxlen=500)
    def probe_health():
        while not health_stop.wait(10):
            stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            try:
                probe = subprocess.run(["adb", "shell", "echo", "ready"], capture_output=True, timeout=5)
                health.append(stamp + " return=" + str(probe.returncode)
                              + " output=" + repr(probe.stdout.decode("utf-8", "replace").strip()))
            except subprocess.TimeoutExpired:
                health.append(stamp + " shell timeout")
    observer = threading.Thread(target=probe_health, daemon=True)
    observer.start()
    try:
        result = subprocess.run([sys.executable, "tools/release/rehearsal.py", "device",
                                 "--folder", "rehearsal", "--api", str(args.api),
                                 "--timeout-seconds", "1000"], cwd=ROOT, timeout=1100).returncode
    finally:
        health_stop.set()
        stream.terminate()
        try:
            stream.wait(timeout=5)
        except subprocess.TimeoutExpired:
            stream.kill()
            stream.wait(timeout=5)
        reader.join(timeout=5)
        observer.join(timeout=7)
        output = ROOT / "test-results/release"
        output.mkdir(parents=True, exist_ok=True)
        (output / "live-system-logcat.txt").write_text("".join(lines)[-200000:])
        (output / "adb-health.txt").write_text("\n".join(health))
        collect()
    raise SystemExit(result)

if __name__ == "__main__":
    sys.path.insert(0, str(ROOT / "scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    main()
