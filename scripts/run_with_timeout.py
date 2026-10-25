#!/usr/bin/env python3
"""Run a command with a finite, inherited deadline and terminate its process tree.

Every project CLI accepts --timeout-seconds N or MIANCAK_SCRIPT_TIMEOUT_SECONDS
(default 3600). Nested commands inherit the remaining budget, never a new hour.
Exit 124 means deadline exceeded; ordinary exit codes and streams are preserved.
"""
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

DEFAULT_SECONDS = 3600
DEADLINE = "MIANCAK_TIMEOUT_DEADLINE"
MARKER = "MIANCAK_TIMEOUT_SCRIPT"


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise ValueError("timeout must be a finite number greater than zero")
    return number


def options(args):
    seconds = positive(os.environ.get("MIANCAK_SCRIPT_TIMEOUT_SECONDS", DEFAULT_SECONDS))
    remaining = []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg == "--":
            remaining.extend(args[index:])
            break
        if arg == "--timeout-seconds":
            index += 1
            if index == len(args):
                raise ValueError("--timeout-seconds requires a value")
            seconds = positive(args[index])
        elif arg.startswith("--timeout-seconds="):
            seconds = positive(arg.split("=", 1)[1])
        else:
            remaining.append(arg)
        index += 1
    return seconds, remaining


def identity(pid):
    """Linux start time prevents signaling an unrelated process after PID reuse."""
    try:
        fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return fields[19], fields[0]
    except (OSError, IndexError):
        return None


def remember_tree(pid, owned):
    value = identity(pid)
    if value is not None:
        owned[pid] = value[0]
    try:
        children = Path(f"/proc/{pid}/task/{pid}/children").read_text().split()
    except OSError:
        return
    for child in children:
        remember_tree(int(child), owned)


def signal_tree(child, owned, sig):
    if os.name == "nt":
        # taskkill /T also reaches children with a new Windows process group.
        subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        return
    remember_tree(child.pid, owned)
    groups = {child.pid}
    for pid, started in list(owned.items()):
        current = identity(pid)
        if current is None or current[0] != started or current[1] == "Z":
            continue
        try:
            group = os.getpgid(pid)
            if group == os.getpgrp():
                os.kill(pid, sig)
            else:
                groups.add(group)
        except ProcessLookupError:
            pass
    groups.discard(os.getpgrp())
    for group in groups:
        try:
            os.killpg(group, sig)
        except ProcessLookupError:
            pass


def run(command, seconds, env=None):
    env = dict(os.environ if env is None else env)
    deadline = time.monotonic() + seconds
    if env.get(DEADLINE):
        inherited = float(env[DEADLINE])
        if not math.isfinite(inherited):
            raise ValueError("invalid inherited deadline")
        deadline = min(deadline, inherited)
    env[DEADLINE] = str(deadline)
    grace = min(10, positive(env.get("MIANCAK_TIMEOUT_GRACE_SECONDS", "3")))
    if deadline <= time.monotonic():
        print("Timeout: inherited script deadline expired", file=sys.stderr, flush=True)
        return 124
    owned = {}
    cancelled = []
    previous = {}
    for sig in (signal.SIGINT, signal.SIGTERM):
        previous[sig] = signal.signal(sig, lambda number, frame: cancelled.append(number))
    try:
        child = subprocess.Popen(command, env=env, start_new_session=(os.name != "nt"))
        while True:
            remember_tree(child.pid, owned)
            code = child.poll()
            if code is not None:
                return code if code >= 0 else 128 - code
            if cancelled or time.monotonic() >= deadline:
                expired = not cancelled
                print("Timeout: script deadline exceeded" if expired else "Script cancelled; terminating children",
                      file=sys.stderr, flush=True)
                signal_tree(child, owned, signal.SIGTERM if expired else cancelled[0])
                end = time.monotonic() + grace
                while time.monotonic() < end:
                    if child.poll() is not None and not any(
                            (value := identity(pid)) and value[0] == started and value[1] != "Z"
                            for pid, started in owned.items()):
                        break
                    time.sleep(.05)
                signal_tree(child, owned, getattr(signal, "SIGKILL", signal.SIGTERM))
                try:
                    child.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    pass
                return 124 if expired else 128 + cancelled[0]
            time.sleep(min(.05, max(.001, deadline - time.monotonic())))
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def script_command(path, args):
    suffix = path.suffix.lower()
    if suffix == ".py":
        return [sys.executable, str(path), *args]
    if suffix == ".bat":
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(path), *args]
    return ["/bin/bash" if suffix == ".sh" else "/bin/sh", str(path), *args]


def guarded_script(path, args):
    path = Path(path).resolve()
    seconds, args = options(args)
    env = dict(os.environ)
    env[MARKER] = os.path.normcase(str(path))
    return run(script_command(path, args), seconds, env)


def guard_script(path):
    if os.environ.get(MARKER) != os.path.normcase(str(Path(path).resolve())):
        try:
            code = guarded_script(path, sys.argv[1:])
        except (ValueError, OSError) as error:
            print(f"Script timeout error: {error}", file=sys.stderr)
            code = 2
        raise SystemExit(code)


def main():
    try:
        if len(sys.argv) >= 3 and sys.argv[1] == "--script":
            args = sys.argv[3:]
            if args[:1] == ["--"]:
                args = args[1:]
            return guarded_script(sys.argv[2], args)
        seconds, args = options(sys.argv[1:])
        if args[:1] in (["--"], ["--command"]):
            args = args[1:]
        if not args:
            raise ValueError("usage: run_with_timeout.py [--timeout-seconds N] -- COMMAND [ARG ...]")
        return run(args, seconds)
    except (ValueError, OSError) as error:
        print(f"Script timeout error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
