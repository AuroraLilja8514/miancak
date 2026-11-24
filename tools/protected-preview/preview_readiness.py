#!/usr/bin/env python3
"""Rehearse protected preview metadata reads and OIDC with fixed synthetic subjects."""
import argparse
from dataclasses import replace
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlencode

import handoff
from install_handoff_tools import install
import manifest_rehearsal as checked
import manifest_request as request
import release_ops as ops

DUMMY_PATH = ".github/release-requests/_protected-preview-readiness-probe-v2.json"
HEAD_BRANCH = "release-request/dummy-preview-readiness-v2"
REVIEW_BRANCH = "review/protected-preview-readiness-v2"
REVIEW_STEP = "Verify preview readiness dummy request and reviewed tree"
WORKFLOW = ".github/workflows/protected-preview-readiness.yml"
SIGNER = ".github/workflows/protected-preview-readiness-signer.yml"
PUBLISHER = ".github/workflows/protected-preview-readiness-publisher.yml"
ROLE_WORKFLOWS = {"signer": SIGNER, "publisher": PUBLISHER}
PREDICATE = f"https://github.com/{ops.REPO}/provenance/preview-readiness/v2"
POLICY = handoff.WorkflowPolicy(
    caller=WORKFLOW, publisher=PUBLISHER, branch="master", event="push",
    title="MIANCAK dummy preview readiness", accept_job="Rehearse signer / Verify synthetic signer proof",
    publish_job="Rehearse publisher / Verify synthetic publisher proof",
    artifact_prefix="preview-readiness", signer=SIGNER,
)


def canonical_dummy(base):
    content = {"format_version": 2, "kind": "dummy-preview-readiness", "reviewed_base_sha": checked.sha(base)}
    return (json.dumps(content, indent=2, sort_keys=True) + "\n").encode()


def pushed(environ, root):
    ops.require(not request.MERGE_ENABLED, "READINESS_PRODUCTION_DISARMED")
    run = environ.get("GITHUB_RUN_ID", "")
    ops.require(re.fullmatch(r"[1-9][0-9]{0,19}", run), "READINESS_RUN_ID")
    revision, base, tree, _ = checked.checked_push(environ, root, workflow=WORKFLOW, path=DUMMY_PATH,
                                                   canonical=canonical_dummy)
    return revision, base, tree, int(run)


def report_metadata_failure(category, authenticated, status, rate_facts):
    auth = "job_token" if authenticated else "anonymous"
    fields = "".join(f" {key}={value}" for key, value in rate_facts.items())
    print(f"READINESS_HTTP_ERROR category={category} auth={auth} status={status}{fields}", file=sys.stderr)


def check_metadata(revision, base, tree, role):
    calls = 0
    start = time.monotonic()

    def fetch(path):
        nonlocal calls
        calls += 1
        return request.metadata_read(path, actions_read=role != "admit", report_failure=report_metadata_failure)

    def check_branch():
        branch = fetch("branches/master")
        checked.expect(branch, {"name": "master", "protected": True}, "READINESS_LIVE_BRANCH")
        checked.expect(branch.get("commit"), {"sha": revision}, "READINESS_LIVE_HEAD")

    check_branch()
    request.require_protection(fetch)
    production_runs = f"actions/workflows/{request.WORKFLOW_ID}/runs?event=push&head_sha={revision}&per_page=2"
    checked.expect(fetch(production_runs), {"total_count": 0, "workflow_runs": []}, "READINESS_PRODUCTION_RUN")
    number, head = checked.merged_pr_head(revision, base, tree, head_branch=HEAD_BRANCH, fetch=fetch)
    checked.require_review_checks(number, head, head_branch=HEAD_BRANCH, review_step=REVIEW_STEP, fetch=fetch)
    for ref in (f"refs/pull/{number}/merge", f"refs/heads/{HEAD_BRANCH}"):
        query = urlencode({"ref": ref, "per_page": 1})
        checked.expect(fetch(f"actions/caches?{query}"), {"total_count": 0, "actions_caches": []}, "READINESS_CACHE")
    check_branch()
    ops.require(calls == 12, "READINESS_READ_COUNT")
    print(f"PASS: READINESS_METADATA role={role} reads={calls} elapsed_ms={int((time.monotonic() - start) * 1000)}")


def fixture_name(role):
    return f"miancak-readiness-{role}.txt"


def fixture_bytes(role):
    return f"MIANCAK synthetic preview readiness {role} v2\n".encode()


def check_proof(role, revision, run, stage, proof):
    subject = stage / fixture_name(role)
    expected = fixture_bytes(role)
    ops.require(subject.is_file() and not subject.is_symlink()
                and subject.stat().st_size == len(expected) and subject.read_bytes() == expected,
                "READINESS_SUBJECT")
    ops.require(proof.is_file() and not proof.is_symlink()
                and 0 < proof.stat().st_size <= handoff.PROOF_BYTES, "READINESS_PROOF_FILE")
    tools = stage / "tools"
    tools.mkdir(mode=0o700)
    install(tools, "gh")
    digest = ops.digest(expected)
    scope = handoff.Scope(run, 1, revision, digest, POLICY)
    workflow = ROLE_WORKFLOWS[role]
    result = handoff.verify_bundle(scope, workflow, PREDICATE, subject, proof, tools)
    statement = handoff.verified_statement(result, scope, workflow, PREDICATE, digest)
    ops.require(statement["subject"][0]["name"] == subject.name
                and statement["predicate"] == {"role": role}, "READINESS_PROOF_SUBJECT")
    different_workflow = PUBLISHER if role == "signer" else SIGNER
    for alternate, callee, sha in (
        (scope, different_workflow, digest),
        (replace(scope, attempt=2), workflow, digest),
        (scope, workflow, ops.digest(b"different synthetic subject")),
    ):
        try:
            handoff.verified_statement(result, alternate, callee, PREDICATE, sha)
        except ops.ReleaseError:
            continue
        raise ops.ReleaseError("READINESS_PROOF_NEGATIVE")
    print(f"PASS: READINESS_OIDC role={role} rejected=workflow,attempt,subject")


def main(argv=None, *, environ=None, root=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("pr-check")
    commands.add_parser("admit")
    for name in ("prepare", "verify"):
        command = commands.add_parser(name)
        command.add_argument("role", choices=ROLE_WORKFLOWS)
        command.add_argument("--stage", type=Path, required=True)
        if name == "verify":
            command.add_argument("--proof", type=Path, required=True)
    args = parser.parse_args(argv)
    environ = os.environ if environ is None else environ
    root = Path.cwd() if root is None else root
    try:
        if args.command == "pr-check":
            checked.pr_check(environ, root, path=DUMMY_PATH, head_branch=HEAD_BRANCH, canonical=canonical_dummy)
            print("PASS: PREVIEW_READINESS_REVIEW")
        else:
            revision, base, tree, run = pushed(environ, root)
            if args.command == "verify":
                check_proof(args.role, revision, run, args.stage, args.proof)
            else:
                role = "admit" if args.command == "admit" else args.role
                check_metadata(revision, base, tree, role)
                if args.command == "prepare":
                    args.stage.mkdir(mode=0o700)
                    data = fixture_bytes(role)
                    (args.stage / fixture_name(role)).write_bytes(data)
                    with Path(environ["GITHUB_OUTPUT"]).open("a") as output:
                        output.write(f"digest=sha256:{ops.digest(data)}\n")
    except (checked.Rejected, ops.ReleaseError) as failure:
        code = str(failure)
        print(f"FAIL: {code if re.fullmatch(r'[A-Z][A-Z0-9_]+', code) else 'READINESS_EVIDENCE'}", file=sys.stderr)
        return 1
    except Exception:
        print("FAIL: READINESS_EVIDENCE", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    raise SystemExit(main())
