#!/usr/bin/env python3
"""Rehearse approval across two hosted runs using a literal dummy receipt."""
import argparse
from dataclasses import replace
from datetime import timedelta
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlencode

import handoff
import preview_ops as preview
import private_inputs as inputs
import release_ops as ops
import smoke_inputs as smoke

LABEL = "MIANCAK dummy"
APPROVE_JOB = "Confirm synthetic owner approval"
POLICY = replace(smoke.POLICY, title=f"{LABEL} start", artifact_prefix="preview-smoke-owner")
HOLD_SECONDS = 360


def approval_title(scope, *, label=LABEL):
    return f"{label} approve-inputs {scope.run_id} {scope.approval}"


def owner_run(run, scope):
    policy = scope.workflow
    return (policy == POLICY and run.get("event") == policy.event and run.get("run_attempt") == scope.attempt == 1
            and run.get("head_branch") == policy.branch and run.get("head_sha") == scope.revision
            and run.get("path") in (policy.caller, f"{policy.caller}@{policy.branch}",
                                    f"{policy.caller}@{policy.ref}")
            and run.get("head_repository", {}).get("id") == ops.REPO_ID
            and preview.owner_dispatch(run))


def approval_origin(run, scope, created, *, label=LABEL):
    asked_at = inputs.timestamp(run.get("created_at"))
    ops.require(owner_run(run, scope) and type(run.get("id")) is int
                and run["id"] > 0 and run["id"] != scope.run_id
                and run.get("display_title") == approval_title(scope, label=label)
                and created <= asked_at <= created + timedelta(seconds=HOLD_SECONDS),
                "PREVIEW_OWNER_APPROVAL_ORIGIN")


def approved_run(scope, created, approval_run_id, *, label=LABEL, job_name=APPROVE_JOB):
    run = ops.api(f"actions/runs/{approval_run_id}", timeout=10)
    approval_origin(run, scope, created, label=label)
    ops.require(run["id"] == approval_run_id, "PREVIEW_OWNER_APPROVAL_ORIGIN")
    if run.get("status") != "completed":
        return None
    ops.require(run.get("conclusion") == "success", "PREVIEW_OWNER_APPROVAL_FAILED")
    approval_scope = replace(scope, run_id=approval_run_id)
    ops.require(handoff.acceptance_job(approval_scope, handoff.jobs_for_attempt(approval_scope),
                                       job_name), "PREVIEW_OWNER_APPROVAL_JOB")
    return approval_run_id


def find_approval(scope, created, *, label=LABEL, job_name=APPROVE_JOB):
    policy = scope.workflow
    query = urlencode({"event": policy.event, "branch": policy.branch, "head_sha": scope.revision,
                       "created": ">=" + created.strftime("%Y-%m-%dT%H:%M:%SZ"), "per_page": 100})
    listing = ops.api(f"actions/workflows/{Path(policy.caller).name}/runs?{query}", timeout=10)
    runs = listing["workflow_runs"]
    ops.require(listing["total_count"] == len(runs) <= 100, "PREVIEW_OWNER_APPROVAL_LIST")
    matches = [run for run in runs if run.get("display_title") == approval_title(scope, label=label)]
    ops.require(len(matches) <= 1, "PREVIEW_OWNER_APPROVAL_AMBIGUOUS")
    if matches:
        approval_origin(matches[0], scope, created, label=label)
        return approved_run(scope, created, matches[0]["id"], label=label, job_name=job_name)
    return None


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
        approved = find_approval(scope, created)
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
    approval_origin(run, target, created)
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
