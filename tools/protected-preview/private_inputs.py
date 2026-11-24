#!/usr/bin/env python3
"""Same-run authenticated build custody for the exact protected preview."""
from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from urllib.parse import urlencode

import handoff
import preview_ops as preview
import release_ops as ops

BUILD_JOB = "Compile private preview inputs"
GATE_JOB = "Authorize private preview inputs"
RECEIPT_STEP = "Upload the exact build receipt"
BUILD_WAIT = 45 * 60
RECEIPT_SECONDS = 22 * 60
SIGNER_START_SECONDS = 40


def identifier(text):
    ops.require(isinstance(text, str) and re.fullmatch(r"[1-9][0-9]{0,19}", text),
                "PREVIEW_INPUT_RUN_ID")
    return int(text)


def timestamp(value):
    ops.require(isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value),
                "PREVIEW_INPUT_TIME")
    return datetime.fromisoformat(value)


def fresh(item, seconds=RECEIPT_SECONDS):
    created = timestamp(item.get("created_at"))
    age = (datetime.now(timezone.utc) - created).total_seconds()
    ops.require(0 <= age <= seconds, "PREVIEW_INPUT_EXPIRED")
    return created


def wait(seconds, check):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        remaining = deadline - time.monotonic()
        if result is not None and remaining > 0:
            return result
        if remaining > 0:
            time.sleep(min(20, remaining))
    raise ops.ReleaseError("PREVIEW_INPUT_DEADLINE")


def artifact(scope, kind):
    query = urlencode({"name": scope.name(kind), "per_page": 2})
    listing = ops.api(f"actions/runs/{scope.run_id}/artifacts?{query}", timeout=10)
    return handoff.matching_artifact(scope, kind, listing)


def load_receipt(scope, item, scratch):
    fresh(item)
    with tempfile.TemporaryDirectory(dir=scratch) as temporary:
        folder = Path(temporary) / "receipt"
        handoff.fetch_wire(scope, "input-receipt", item, folder)
        raw = (folder / "receipt.json").read_bytes()
    record = json.loads(raw)
    preview.validate_input_record(record, scope.revision, scope.run_id)
    ops.require(raw == preview.canonical_record(record), "PREVIEW_INPUT_RECEIPT_FORMAT")
    return record


def finished(scope, name):
    return handoff.acceptance_job(scope, handoff.jobs_for_attempt(scope), name)


def builder_receipt(scope):
    handoff.active_run(scope)
    builder = handoff.select_job(scope, handoff.jobs_for_attempt(scope), BUILD_JOB)
    if builder is None:
        return None
    status, result = builder.get("status"), builder.get("conclusion")
    ops.require((status == "completed" and result == "success")
                or (status in ("queued", "in_progress", "waiting", "pending", "requested") and result is None),
                "PREVIEW_INPUT_BUILDER_STATE")
    if status not in ("in_progress", "completed"):
        return None
    steps = [step for step in builder.get("steps", []) if step["name"] == RECEIPT_STEP]
    ops.require(len(steps) <= 1, "PREVIEW_INPUT_BUILDER_STEP")
    if not steps or steps[0]["status"] != "completed":
        return None
    ops.require(steps[0]["conclusion"] == "success", "PREVIEW_INPUT_BUILDER_STEP")
    ops.require(builder.get("runner_group_name") == "GitHub Actions"
                and builder.get("labels") == ["ubuntu-24.04"], "PREVIEW_INPUT_BUILDER_RUNNER")
    item = artifact(scope, "input-receipt")
    if item is None:
        return None
    fresh(item)
    return builder["id"], item


def grant_record(scope, builder_id, item):
    return {**scope.record(), "builder_job_id": builder_id,
            "receipt": {key: item[key] for key in ("id", "digest", "size_in_bytes", "created_at")}}


def authorize(scope, stage):
    builder_id, item = wait(BUILD_WAIT, lambda: builder_receipt(scope))
    record = load_receipt(scope, item, stage)
    scope = replace(scope, approval=preview.attestation_sha(record))
    ops.require(preview.authority("publish") == scope.revision, "PREVIEW_TRUSTED_REVISION")
    preview.verify_request(scope.revision, actions_read=True)
    ops.require(builder_receipt(scope) == (builder_id, item), "PREVIEW_INPUT_RECEIPT_CHANGED")
    (stage / "grant.json").write_bytes(preview.canonical_record(grant_record(scope, builder_id, item)))
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as stream:
        stream.write(f"approval_sha256={scope.approval}\n")
    print(f"PASS: verified build receipt {scope.approval} for run {scope.run_id}, attempt 1")


def confirm_grant(scope, stage):
    grant_id = identifier(os.environ.get("PREVIEW_GRANT_ARTIFACT_ID", ""))
    handoff.active_run(scope)
    ops.require(finished(scope, GATE_JOB), "PREVIEW_INPUT_GATE_JOB")
    receipt = builder_receipt(scope)
    ops.require(receipt is not None, "PREVIEW_INPUT_RECEIPT_UNAVAILABLE")
    builder_id, item = receipt
    grant = artifact(scope, "input-grant")
    ops.require(grant is not None and grant["id"] == grant_id, "PREVIEW_INPUT_GRANT_CHANGED")
    with tempfile.TemporaryDirectory(dir=stage) as temporary:
        folder = Path(temporary) / "grant"
        handoff.fetch_wire(scope, "input-grant", grant, folder)
        raw = (folder / "grant.json").read_bytes()
        ops.require(raw == preview.canonical_record(grant_record(scope, builder_id, item)),
                    "PREVIEW_INPUT_GRANT_RECORD")
        handoff.verify_proof(scope, "input-grant", folder, stage / "tools")
    record = load_receipt(scope, item, stage)
    preview.require_approval(record, scope.approval)
    ops.require(builder_receipt(scope) == receipt, "PREVIEW_INPUT_RECEIPT_CHANGED")
    ops.require(artifact(scope, "input-grant") == grant, "PREVIEW_INPUT_GRANT_CHANGED")
    return record


def seal_inputs(scope, stage):
    record = json.loads((stage / "receipt/receipt.json").read_bytes())
    preview.require_approval(record, scope.approval)

    def current_recipient(startup_phase=None):
        if not handoff.active_run(scope, startup_phase=startup_phase):
            return None
        item = artifact(scope, "input-receipt")
        if item is not None:
            fresh(item)
        if not finished(scope, GATE_JOB):
            return None
        signer = handoff.select_job(scope, handoff.jobs_for_attempt(scope), handoff.SIGN_JOB)
        if signer is None:
            return None
        status = signer.get("status")
        ops.require(signer.get("conclusion") is None
                    and status in ("in_progress", *handoff.SIGNER_STARTUP_STATES), "PREVIEW_INPUT_SIGNER_STATE")
        return artifact(scope, "input-recipient") if status == "in_progress" else None

    item = wait(RECEIPT_SECONDS, lambda: current_recipient("builder-await-signer"))
    handoff.active_run(scope)
    recipient = stage / "recipient"
    handoff.fetch_wire(scope, "input-recipient", item, recipient)
    tools = stage / "tools"
    handoff.verify_proof(scope, "input-recipient", recipient, tools)
    staged = stage / "inputs"
    ops.require(preview.input_record(staged, scope.revision, scope.run_id) == record,
                "PREVIEW_PRIVATE_INPUT_CHANGED")
    ops.require(current_recipient() == item, "PREVIEW_INPUT_RECIPIENT_CHANGED")
    handoff.active_run(scope)
    handoff.encrypt_files(scope, recipient / "recipient.json", staged,
                          {name: entry["bytes"] for name, entry in record["apks"].items()},
                          stage / "inputs.age", tools, limit=handoff.MAX_INPUT_CIPHERTEXT)


def receive_inputs(scope, stage):
    record = confirm_grant(scope, stage)

    def current_ciphertext():
        handoff.active_run(scope)
        if finished(scope, BUILD_JOB):
            return artifact(scope, "input-ciphertext")
        return None

    item = wait(4 * 60, current_ciphertext)
    folder = stage / "input-ciphertext"
    handoff.fetch_wire(scope, "input-ciphertext", item, folder)
    prepared = stage / "prepared"
    handoff.active_run(scope)
    handoff.decrypt_files(scope, stage / "recipient/recipient.json", stage / "identity.txt",
                          folder / "inputs.age", prepared,
                          {name: entry["bytes"] for name, entry in record["apks"].items()},
                          stage / "tools", limit=handoff.MAX_INPUT_CIPHERTEXT)
    ops.require(confirm_grant(scope, stage) == record, "PREVIEW_INPUT_RECEIPT_CHANGED")
    preview.prepare_private(prepared, scope.revision, scope.run_id, record,
                            {"id": item["id"], "digest": item["digest"]}, scope.approval)


def run(args):
    stage = args.stage
    operation = args.operation
    run_id = identifier(os.environ.get("GITHUB_RUN_ID", ""))
    revision = preview.authority("publish")
    approval = os.environ.get("PREVIEW_APPROVAL_SHA256", "")
    if operation == "seal":
        approval = ops.file_sha(stage / "receipt/receipt.json")
    if operation in ("seal", "recipient", "receive", "pre-key"):
        ops.require(preview.hex_digest(approval), "PREVIEW_APPROVAL_REQUIRED")
    scope = handoff.production_scope(run_id, revision, approval)
    if operation == "recipient":
        wait(SIGNER_START_SECONDS, lambda: handoff.active_run(scope, startup_phase="signer-startup"))
    elif operation != "seal":
        handoff.active_run(scope)
    if operation == "stage":
        record = preview.input_record(stage / "inputs", revision, run_id)
        folder = stage / "receipt"
        folder.mkdir(mode=0o700)
        (folder / "receipt.json").write_bytes(preview.canonical_record(record))
        print(f"Review run {run_id}, attempt 1, receipt SHA-256 {preview.attestation_sha(record)}")
        print(json.dumps(record, sort_keys=True))
    elif operation == "authorize":
        authorize(scope, stage)
    elif operation == "seal":
        seal_inputs(scope, stage)
    elif operation == "recipient":
        confirm_grant(scope, stage)
        handoff.active_run(scope)
        handoff.create_recipient(scope, stage, stage / "tools")
    elif operation == "receive":
        receive_inputs(scope, stage)
    else:
        confirm_grant(scope, stage)
        ops.require(finished(scope, BUILD_JOB), "PREVIEW_INPUT_BUILDER_STATE")
        ops.require(preview.authority("publish") == scope.revision, "PREVIEW_TRUSTED_REVISION")
        preview.bind_signing_input(stage / "prepared", revision, approval)
        preview.request_pre_key(stage / "prepared", revision, approval, record=True)


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("stage", "authorize", "seal", "recipient",
                                              "receive", "pre-key"))
    parser.add_argument("--stage", type=Path, required=True)
    try:
        run(parser.parse_args())
    except ops.ReleaseError as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("FAIL: PREVIEW_PRIVATE_INPUT_OPERATION", file=sys.stderr)
        raise SystemExit(1)
