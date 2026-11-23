#!/usr/bin/env python3
"""Same-run build custody and owner approval for the protected preview."""
from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timedelta, timezone
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

OWNER_ID = 315702774
BUILD_JOB = "Compile private preview inputs"
GATE_JOB = "Authorize private preview inputs"
APPROVE_JOB = "Confirm exact preview input receipt"
BUILD_WAIT = 45 * 60
REVIEW_SECONDS = 15 * 60
RECEIPT_SECONDS = 22 * 60


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


def approval_title(scope):
    return f"{preview.TAG} approve-inputs {scope.run_id} {scope.approval}"


def owner_run(run, scope):
    return (run.get("event") == "workflow_dispatch" and run.get("run_attempt") == 1
            and run.get("head_branch") == "master" and run.get("head_sha") == scope.revision
            and preview.matches_workflow(run.get("path"))
            and run.get("head_repository", {}).get("id") == ops.REPO_ID
            and all(run.get(field, {}).get("id") == OWNER_ID
                    and preview.approved_actor(run.get(field, {}).get("login", ""))
                    for field in ("actor", "triggering_actor")))


def approval_origin(run, scope, created):
    asked_at = timestamp(run.get("created_at"))
    ops.require(owner_run(run, scope) and type(run.get("id")) is int
                and run["id"] > 0 and run["id"] != scope.run_id
                and run.get("display_title") == approval_title(scope)
                and created <= asked_at <= created + timedelta(seconds=REVIEW_SECONDS),
                "PREVIEW_OWNER_APPROVAL_ORIGIN")


def approved_run(scope, created, approval_run_id):
    run = ops.api(f"actions/runs/{approval_run_id}", timeout=10)
    approval_origin(run, scope, created)
    ops.require(run["id"] == approval_run_id, "PREVIEW_OWNER_APPROVAL_ORIGIN")
    if run.get("status") != "completed":
        return None
    ops.require(run.get("conclusion") == "success", "PREVIEW_OWNER_APPROVAL_FAILED")
    approval_scope = replace(scope, run_id=approval_run_id)
    ops.require(handoff.acceptance_job(approval_scope, handoff.jobs_for_attempt(approval_scope),
                                       APPROVE_JOB), "PREVIEW_OWNER_APPROVAL_JOB")
    return approval_run_id


def find_approval(scope, created):
    query = urlencode({"event": "workflow_dispatch", "branch": "master", "head_sha": scope.revision,
                       "created": ">=" + created.strftime("%Y-%m-%dT%H:%M:%SZ"), "per_page": 100})
    listing = ops.api(f"actions/workflows/{Path(preview.WORKFLOW).name}/runs?{query}", timeout=10)
    runs = listing["workflow_runs"]
    ops.require(listing["total_count"] == len(runs) <= 100, "PREVIEW_OWNER_APPROVAL_LIST")
    matches = [run for run in runs if run.get("display_title") == approval_title(scope)]
    ops.require(len(matches) <= 1, "PREVIEW_OWNER_APPROVAL_AMBIGUOUS")
    if matches:
        approval_origin(matches[0], scope, created)
        return approved_run(scope, created, matches[0]["id"])
    return None


def finished(scope, name):
    return handoff.acceptance_job(scope, handoff.jobs_for_attempt(scope), name)


def write_request(scope, approval_id, stage):
    handoff.active_run(scope)
    item = artifact(scope, "input-receipt")
    ops.require(item is not None, "PREVIEW_INPUT_RECEIPT_UNAVAILABLE")
    created = fresh(item, REVIEW_SECONDS)
    record = load_receipt(scope, item, stage)
    preview.require_approval(record, scope.approval)
    run = ops.api(f"actions/runs/{approval_id}", timeout=10)
    approval_origin(run, scope, created)
    ops.require(run["id"] == approval_id and run.get("status") == "in_progress",
                "PREVIEW_OWNER_APPROVAL_ORIGIN")
    print("PASS: exact owner approval request validated")


def authorize(scope, stage):
    def current_receipt():
        handoff.active_run(scope)
        return artifact(scope, "input-receipt")

    item = wait(BUILD_WAIT, current_receipt)
    record = load_receipt(scope, item, stage)
    scope = replace(scope, approval=preview.attestation_sha(record))
    created = fresh(item, REVIEW_SECONDS)
    summary = Path(os.environ["GITHUB_STEP_SUMMARY"])
    with summary.open("a") as stream:
        stream.write(f"### Approve exact preview inputs\n\nRun `{scope.run_id}`, attempt `1`\n\n"
                     f"Receipt SHA-256 `{scope.approval}`\n\n"
                     f"```json\n{json.dumps(record, sort_keys=True, indent=2)}\n```\n")
    print(f"Review run {scope.run_id} and receipt SHA-256 {scope.approval}", flush=True)

    def current_approval():
        fresh(item, REVIEW_SECONDS)
        ops.require(current_receipt() == item, "PREVIEW_INPUT_RECEIPT_CHANGED")
        approved = find_approval(scope, created)
        fresh(item, REVIEW_SECONDS)
        return approved

    approval_id = wait(REVIEW_SECONDS, current_approval)
    ops.require(preview.authority("publish") == scope.revision, "PREVIEW_TRUSTED_REVISION")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as stream:
        stream.write(f"approval_sha256={scope.approval}\napproval_run_id={approval_id}\n"
                     f"receipt_artifact_id={item['id']}\n")


def confirm_grant(scope, stage):
    approval_id = identifier(os.environ.get("PREVIEW_APPROVAL_RUN_ID", ""))
    receipt_id = identifier(os.environ.get("PREVIEW_RECEIPT_ARTIFACT_ID", ""))
    handoff.active_run(scope)
    ops.require(finished(scope, GATE_JOB), "PREVIEW_INPUT_GATE_JOB")
    item = artifact(scope, "input-receipt")
    ops.require(item is not None and item["id"] == receipt_id, "PREVIEW_INPUT_RECEIPT_CHANGED")
    record = load_receipt(scope, item, stage)
    preview.require_approval(record, scope.approval)
    ops.require(find_approval(scope, fresh(item)) == approval_id, "PREVIEW_OWNER_APPROVAL_REQUIRED")
    fresh(item)
    return record


def seal_inputs(scope, stage):
    record = json.loads((stage / "receipt/receipt.json").read_bytes())
    preview.require_approval(record, scope.approval)

    def current_recipient():
        handoff.active_run(scope)
        item = artifact(scope, "input-receipt")
        if item is not None:
            fresh(item)
        if not finished(scope, GATE_JOB):
            return None
        signer = handoff.select_job(scope, handoff.jobs_for_attempt(scope), handoff.SIGN_JOB)
        if signer and signer.get("status") == "in_progress" and signer.get("conclusion") is None:
            return artifact(scope, "input-recipient")
        return None

    item = wait(RECEIPT_SECONDS, current_recipient)
    recipient = stage / "recipient"
    handoff.fetch_wire(scope, "input-recipient", item, recipient)
    tools = stage / "tools"
    handoff.verify_proof(scope, "input-recipient", recipient, tools)
    staged = stage / "inputs"
    ops.require(preview.input_record(staged, scope.revision, scope.run_id) == record,
                "PREVIEW_PRIVATE_INPUT_CHANGED")
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
    if operation == "request":
        requested = os.environ.get("PREVIEW_REQUEST_SHA256", "")
        ops.require(preview.hex_digest(requested), "PREVIEW_APPROVAL_REQUIRED")
        target = identifier(os.environ.get("PREVIEW_TARGET_RUN_ID", ""))
        revision = preview.authority("approve-inputs")
        write_request(handoff.Scope(target, 1, revision, requested), run_id, stage)
        return
    revision = preview.authority("publish")
    approval = os.environ.get("PREVIEW_APPROVAL_SHA256", "")
    if operation == "seal":
        approval = ops.file_sha(stage / "receipt/receipt.json")
    if operation in ("seal", "recipient", "receive", "pre-key"):
        ops.require(preview.hex_digest(approval), "PREVIEW_APPROVAL_REQUIRED")
    scope = handoff.Scope(run_id, 1, revision, approval)
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
        handoff.create_recipient(scope, stage, stage / "tools")
    elif operation == "receive":
        receive_inputs(scope, stage)
    else:
        confirm_grant(scope, stage)
        preview.bind_signing_input(stage / "prepared", revision, approval)


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("stage", "authorize", "request", "seal", "recipient",
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
