#!/usr/bin/env python3
"""Rehearse approval across two hosted runs using a literal dummy receipt."""
import argparse
from dataclasses import replace
import os
from pathlib import Path
import re
import sys

import handoff
import private_inputs as inputs
import release_ops as ops
import smoke_inputs as smoke

LABEL = "MIANCAK dummy"
APPROVE_JOB = "Confirm synthetic owner approval"
POLICY = replace(smoke.POLICY, title=f"{LABEL} start", artifact_prefix="preview-smoke-owner")
HOLD_SECONDS = 360


def stage_receipt(scope, stage):
    directory = stage / "owner-smoke-receipt"
    directory.mkdir(mode=0o700)
    (directory / "receipt.json").write_bytes(smoke.receipt_bytes(scope))
    print(f"Dummy receipt for run {scope.run_id}, attempt 1, SHA-256 {scope.approval}")


def current_receipt(scope):
    handoff.active_run(scope)
    item = inputs.artifact(scope, "input-receipt")
    ops.require(item is not None, "PREVIEW_INPUT_RECEIPT_UNAVAILABLE")
    return item


def wait_for_owner(scope, stage):
    item = current_receipt(scope)
    smoke.check_receipt(scope, item, stage)

    def approval():
        created = inputs.fresh(item, HOLD_SECONDS)
        ops.require(current_receipt(scope) == item, "PREVIEW_INPUT_RECEIPT_CHANGED")
        approved = inputs.find_approval(scope, created, label=LABEL, job_name=APPROVE_JOB)
        inputs.fresh(item, HOLD_SECONDS)
        return approved

    approved = inputs.wait(HOLD_SECONDS, approval)
    print(f"PASS: exact dummy receipt approved by successful owner run {approved}, attempt 1")


def approve(scope, stage, request):
    match = re.fullmatch(r"approve-inputs ([1-9][0-9]{0,19}) ([0-9a-f]{64})", request)
    ops.require(match, "OWNER_SMOKE_REQUEST")
    target = replace(scope, run_id=int(match[1]), approval=match[2], workflow=POLICY)
    item = current_receipt(target)
    created = inputs.fresh(item, HOLD_SECONDS)
    smoke.check_receipt(target, item, stage)
    run = ops.api(f"actions/runs/{scope.run_id}", timeout=10)
    inputs.approval_origin(run, target, created, label=LABEL)
    ops.require(run["id"] == scope.run_id and run.get("status") == "in_progress",
                "PREVIEW_OWNER_APPROVAL_ORIGIN")
    print("PASS: authenticated a distinct owner dispatch and the exact in-progress dummy receipt")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("stage", "wait", "approve"))
    parser.add_argument("--stage", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.operation == "approve":
            scope = smoke.current_scope(replace(POLICY, title=None))
            approve(scope, args.stage, os.environ.get("MIANCAK_OWNER_PROBE", ""))
        else:
            scope = smoke.current_scope(POLICY)
            operation = stage_receipt if args.operation == "stage" else wait_for_owner
            operation(scope, args.stage)
    except ops.ReleaseError as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("FAIL: OWNER_SMOKE_OPERATION", file=sys.stderr)
        raise SystemExit(1)
