#!/usr/bin/env python3
"""Hosted inbound transfer rehearsal. This entry point only handles dummy bytes."""
from dataclasses import replace
import argparse
import os
from pathlib import Path
import re
import sys
import tempfile

import handoff
import preview_ops as preview
import private_inputs as inputs
import release_ops as ops
from smoke_handoff import require_rejection

BUILD = "Hold synthetic preview inputs"
APPROVE = "Approve synthetic receipt"
GATE = "Authorize synthetic inputs"
RECEIVE = "Receive synthetic preview inputs / Verify private input ciphertext"
RECEIVER_WORKFLOW = ".github/workflows/protected-preview-inbound-smoke.yml"
POLICY = handoff.WorkflowPolicy(
    ".github/workflows/ci.yml", RECEIVER_WORKFLOW,
    "review/protected-preview-ciphertext", "workflow_dispatch", None,
    BUILD, RECEIVE, "preview-smoke-inbound",
    RECEIVER_WORKFLOW,
)
PAYLOADS = {name: f"MIANCAK synthetic inbound test {name}\n".encode()
            for name in ("build.bin", "driver.bin", "test.bin")}
FILES = {name: len(payload) for name, payload in PAYLOADS.items()}


def receipt_bytes(scope):
    return preview.canonical_record({
        "purpose": "synthetic-inbound", "repository_id": ops.REPO_ID,
        "run_id": scope.run_id, "attempt": scope.attempt, "revision": scope.revision,
        "files": {name: {"sha256": ops.digest(payload), "bytes": len(payload)}
                  for name, payload in PAYLOADS.items()},
    })


def current_scope(policy=POLICY):
    env = os.environ
    revision = env.get("GITHUB_SHA", "")
    ops.require(re.fullmatch(r"[0-9a-f]{40}", revision)
                and env.get("GITHUB_REPOSITORY") == ops.REPO
                and env.get("GITHUB_REPOSITORY_ID") == str(ops.REPO_ID)
                and env.get("GITHUB_ACTOR_ID") == str(inputs.OWNER_ID)
                and preview.approved_actor(env.get("GITHUB_TRIGGERING_ACTOR", ""))
                and env.get("GITHUB_REF") == policy.ref and env.get("GITHUB_EVENT_NAME") == policy.event
                and env.get("GITHUB_WORKFLOW_REF") == f"{ops.REPO}/{policy.caller}@{policy.ref}"
                and env.get("GITHUB_WORKFLOW_SHA") == revision and env.get("GITHUB_RUN_ATTEMPT") == "1",
                "INPUT_SMOKE_SCOPE")
    checkout = ops.command(["git", "-C", Path(__file__).resolve().parents[2], "rev-parse", "HEAD"],
                           timeout=10).stdout.decode().strip()
    ops.require(checkout == revision, "INPUT_SMOKE_CHECKOUT")
    scope = handoff.Scope(inputs.identifier(env.get("GITHUB_RUN_ID", "")), 1, revision, "", policy)
    scope = replace(scope, approval=ops.digest(receipt_bytes(scope)))
    inputs.wait(40, lambda: handoff.active_run(scope, starting=True))
    return scope


def stage_dummy(scope, stage):
    source = stage / "inputs"
    source.mkdir(mode=0o700)
    for filename, payload in PAYLOADS.items():
        (source / filename).write_bytes(payload)
    receipt = stage / "receipt"
    receipt.mkdir(mode=0o700)
    (receipt / "receipt.json").write_bytes(receipt_bytes(scope))


def running(job):
    return job is not None and job.get("status") == "in_progress" and job.get("conclusion") is None


def check_receipt(scope, item, stage):
    inputs.fresh(item, 480)
    with tempfile.TemporaryDirectory(dir=stage) as tmp:
        directory = Path(tmp) / "receipt"
        handoff.fetch_wire(scope, "input-receipt", item, directory)
        raw = (directory / "receipt.json").read_bytes()
        ops.require(raw == receipt_bytes(scope) and ops.digest(raw) == scope.approval,
                    "INPUT_SMOKE_RECEIPT")


def approve(scope, stage):
    def overlap():
        if not handoff.active_run(scope, starting=True):
            return None
        jobs = handoff.jobs_for_attempt(scope)
        if all(running(handoff.select_job(scope, jobs, name)) for name in (BUILD, GATE)):
            return inputs.artifact(scope, "input-receipt")
        return None

    item = inputs.wait(300, overlap)
    check_receipt(scope, item, stage)
    require_rejection("PREVIEW_INPUT_EXPIRED", inputs.fresh, item | {"created_at": "1970-01-01T00:00:00Z"})
    print("PASS: dummy receipt approved with builder and gate in progress; expired receipt rejected")


def gate(scope, stage):
    def granted():
        if not handoff.active_run(scope, starting=True):
            return None
        if inputs.finished(scope, APPROVE):
            return inputs.artifact(scope, "input-receipt")
        return None

    item = inputs.wait(330, granted)
    check_receipt(scope, item, stage)
    require_rejection("HANDOFF_JOB_ORIGIN", handoff.select_job, replace(scope, attempt=2),
                      {"jobs": [{"name": APPROVE, "id": 1, "run_id": scope.run_id,
                                 "head_sha": scope.revision, "run_attempt": 1}], "total_count": 1}, APPROVE)
    print("PASS: authorization waited for successful dummy approval in the exact attempt")


def seal(scope, stage):
    def ready():
        if not handoff.active_run(scope, starting=True):
            return None
        if inputs.finished(scope, GATE) and handoff.publisher_running(scope):
            return inputs.artifact(scope, "input-recipient")
        return None

    item = inputs.wait(420, ready)
    target = stage / "recipient"
    handoff.fetch_wire(scope, "input-recipient", item, target)
    tools = stage / "tools"
    proof = handoff.verify_proof(scope, "input-recipient", target, tools)
    digest = ops.file_sha(target / "recipient.json")
    for foreign in (replace(scope, run_id=scope.run_id + 1), replace(scope, attempt=2)):
        require_rejection("HANDOFF_ATTESTATION_ORIGIN", handoff.verified_subject,
                          proof, foreign, "input-recipient", digest)
    ops.require(handoff.publisher_running(scope), "INPUT_SMOKE_RECEIVER_EXITED")
    handoff.encrypt_files(scope, target / "recipient.json", stage / "inputs", FILES,
                          stage / "inputs.age", tools, limit=handoff.MAX_INPUT_CIPHERTEXT)
    print("PASS: authenticated live reusable receiver and sealed dummy inputs; replay rejected")


def recipient(scope, stage):
    ops.require(inputs.finished(scope, GATE), "INPUT_SMOKE_GATE")
    handoff.create_recipient(scope, stage, stage / "tools")


def receive(scope, stage):
    def ready():
        if not handoff.active_run(scope, starting=True):
            return None
        if inputs.finished(scope, BUILD):
            return inputs.artifact(scope, "input-ciphertext")
        return None

    handoff.fetch_wire(scope, "input-ciphertext", inputs.wait(240, ready), stage / "ciphertext")
    tools = stage / "tools"
    recipient_path = stage / "recipient/recipient.json"
    identity = stage / "identity.txt"
    ciphertext = stage / "ciphertext/inputs.age"
    with tempfile.TemporaryDirectory(dir=stage) as tmp:
        root = Path(tmp)
        recovered = root / "recovered"
        handoff.decrypt_files(scope, recipient_path, identity, ciphertext, recovered, FILES, tools,
                              limit=handoff.MAX_INPUT_CIPHERTEXT)
        ops.require(all((recovered / name).read_bytes() == payload for name, payload in PAYLOADS.items()),
                    "INPUT_SMOKE_REHASH")
        tampered = bytearray(ciphertext.read_bytes())
        tampered[-1] ^= 1
        (root / "tampered.age").write_bytes(tampered)
        require_rejection("TOOL_FAILED", handoff.decrypt_files, scope, recipient_path, identity,
                          root / "tampered.age", root / "bad", FILES, tools)
    print("PASS: same-attempt builder success checked, exact dummy inputs recovered, and tampering rejected")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    operations = {"stage": stage_dummy, "approve": approve, "gate": gate,
                  "seal": seal, "recipient": recipient, "receive": receive}
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=operations)
    parser.add_argument("--stage", required=True, type=Path)
    try:
        args = parser.parse_args()
        operations[args.operation](current_scope(), args.stage)
    except ops.ReleaseError as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("FAIL: INPUT_SMOKE_OPERATION", file=sys.stderr)
        raise SystemExit(1)
