#!/usr/bin/env python3
"""Exercise the reviewed transfer with a fixed synthetic payload."""
from __future__ import annotations

import argparse
from dataclasses import replace
import os
from pathlib import Path
import re
import sys
import tempfile

import handoff
import release_ops as ops

POLICY = handoff.WorkflowPolicy(
    ".github/workflows/protected-preview-smoke.yml",
    ".github/workflows/protected-preview-smoke-publisher.yml",
    "review/protected-preview-ciphertext", "push", "MIANCAK synthetic ciphertext handoff",
    "Seal synthetic handoff", "Receive synthetic handoff / Verify synthetic bytes", "preview-smoke",
)
PAYLOAD = b"MIANCAK synthetic handoff payload v1\n"
FILES = {"synthetic.txt": len(PAYLOAD)}


def current_scope():
    env = os.environ
    run_id = env.get("GITHUB_RUN_ID", "")
    revision = env.get("GITHUB_SHA", "")
    ops.require(env.get("GITHUB_REPOSITORY") == ops.REPO
                and env.get("GITHUB_REPOSITORY_ID") == str(ops.REPO_ID)
                and env.get("GITHUB_REF") == POLICY.ref and env.get("GITHUB_EVENT_NAME") == POLICY.event
                and env.get("GITHUB_WORKFLOW_REF") == f"{ops.REPO}/{POLICY.caller}@{POLICY.ref}"
                and env.get("GITHUB_WORKFLOW_SHA") == revision and env.get("GITHUB_RUN_ATTEMPT") == "1"
                and re.fullmatch(r"[0-9a-f]{40}", revision)
                and re.fullmatch(r"[1-9][0-9]{0,19}", run_id), "SMOKE_SCOPE")
    checkout = ops.command(["git", "-C", Path(__file__).resolve().parents[2], "rev-parse", "HEAD"],
                           timeout=10).stdout.decode().strip()
    ops.require(checkout == revision, "SMOKE_CHECKOUT")
    scope = handoff.Scope(int(run_id), 1, revision, ops.digest(PAYLOAD), POLICY)
    handoff.active_run(scope)
    return scope


def require_rejection(code, check, *args):
    try:
        check(*args)
    except ops.ReleaseError as failure:
        if str(failure) == code:
            return
        raise
    raise ops.ReleaseError("SMOKE_EXPECTED_REJECTION")


def seal(scope, stage, tools):
    item = handoff.wait_for_transport(scope, "recipient", 240)
    folder = stage / "recipient"
    handoff.fetch_wire(scope, "recipient", item, folder)
    proof = handoff.verify_proof(scope, "recipient", folder, tools)
    recipient = folder / "recipient.json"
    digest = ops.file_sha(recipient)
    for stale in (replace(scope, run_id=scope.run_id + 1), replace(scope, attempt=scope.attempt + 1)):
        require_rejection("HANDOFF_ATTESTATION_ORIGIN", handoff.verified_subject,
                          proof, stale, "recipient", digest)
    require_rejection("HANDOFF_ARTIFACT_AMBIGUOUS", handoff.matching_artifact,
                      scope, "recipient", {"total_count": 2, "artifacts": [item, item]})
    ops.require(handoff.publisher_running(scope), "SMOKE_RECEIVER_UNAVAILABLE")
    outgoing = stage / "ciphertext"
    outgoing.mkdir(mode=0o700)
    with tempfile.TemporaryDirectory(dir=stage) as temporary:
        source = Path(temporary)
        (source / "synthetic.txt").write_bytes(PAYLOAD)
        handoff.encrypt_files(scope, recipient, source, FILES, outgoing / "accepted.age", tools)
    print("PASS: Authenticated the running receiver and rejected replay and ambiguous artifacts")


def receive(scope, stage, tools):
    item = handoff.wait_for_transport(scope, "ciphertext", 360)
    folder = stage / "ciphertext"
    handoff.fetch_wire(scope, "ciphertext", item, folder)
    handoff.verify_proof(scope, "ciphertext", folder, tools)
    recipient = stage / "recipient/recipient.json"
    identity = stage / "identity.txt"
    ciphertext = folder / "accepted.age"
    with tempfile.TemporaryDirectory(dir=stage) as temporary:
        root = Path(temporary)
        decoded = root / "decoded"
        handoff.decrypt_files(scope, recipient, identity, ciphertext, decoded, FILES, tools)
        payload = (decoded / "synthetic.txt").read_bytes()
        ops.require(payload == PAYLOAD and ops.digest(payload) == scope.approval, "SMOKE_DIGEST")
        wrong = root / "wrong"
        wrong.mkdir(mode=0o700)
        handoff.create_recipient(scope, wrong, tools)
        require_rejection("TOOL_FAILED", handoff.decrypt_files, scope, wrong / "recipient/recipient.json",
                          wrong / "identity.txt", ciphertext, root / "wrong-decoded", FILES, tools)
        tampered = bytearray(ciphertext.read_bytes())
        tampered[-1] ^= 1
        (root / "tampered.age").write_bytes(tampered)
        require_rejection("TOOL_FAILED", handoff.decrypt_files, scope, recipient, identity,
                          root / "tampered.age", root / "tampered-decoded", FILES, tools)
    handoff.active_run(scope)
    ops.require(handoff.accepted(scope), "SMOKE_ACCEPTANCE")
    print("PASS: Verified successful sender, recovered the exact dummy bytes, and rejected tampering and wrong keys")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("recipient", "seal", "receive"))
    parser.add_argument("--stage", type=Path, required=True)
    args = parser.parse_args()
    try:
        scope = current_scope()
        operations = {"recipient": handoff.create_recipient, "seal": seal, "receive": receive}
        operations[args.operation](scope, args.stage, args.stage / "tools")
    except ops.ReleaseError as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("FAIL: SMOKE_OPERATION", file=sys.stderr)
        raise SystemExit(1)
