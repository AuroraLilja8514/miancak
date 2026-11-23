#!/usr/bin/env python3
"""Same-attempt authenticated, ciphertext-only transport for the preview."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import time
from urllib.parse import urlencode
from zipfile import ZipFile

import preview_ops as preview
import release_ops as ops

PUBLISHER = ".github/workflows/protected-preview-publisher.yml"
SIGNER = ".github/workflows/protected-preview-signer.yml"
SIGN_JOB = "Accept signed preview / Sign and test preview"
PUBLISH_JOB = "Publish preview / Receive accepted preview"
PREDICATE = f"https://github.com/{ops.REPO}/provenance/preview-handoff/v1"
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
MAX_CIPHERTEXT = 64 * 1024 * 1024
MAX_INPUT_CIPHERTEXT = 96 * 1024 * 1024
PROOF_BYTES = 128 * 1024
RECEIPT_BYTES = 4096
TRANSPORT = {
    "recipient": ("recipient.json", RECEIPT_BYTES, 256 * 1024),
    "ciphertext": ("accepted.age", MAX_CIPHERTEXT, MAX_CIPHERTEXT + 256 * 1024),
    "input-receipt": ("receipt.json", RECEIPT_BYTES, 16 * 1024),
    "input-recipient": ("recipient.json", RECEIPT_BYTES, 256 * 1024),
    "input-ciphertext": ("inputs.age", MAX_INPUT_CIPHERTEXT, MAX_INPUT_CIPHERTEXT + 256 * 1024),
}
RECEIPT = "handoff.json"
RECIPIENT_PATTERN = r"age1[0-9a-z]{58}"


@dataclass(frozen=True)
class WorkflowPolicy:
    caller: str
    publisher: str
    branch: str
    event: str
    title: str | None
    accept_job: str
    publish_job: str
    artifact_prefix: str
    signer: str | None = None

    @property
    def ref(self):
        return "refs/heads/" + self.branch

    def signer_for(self, kind):
        return self.publisher if kind == "recipient" else self.signer or self.caller


PRODUCTION = WorkflowPolicy(preview.WORKFLOW, PUBLISHER, "master", "workflow_dispatch",
                            f"{preview.TAG} publish", SIGN_JOB, PUBLISH_JOB, "preview", SIGNER)


@dataclass(frozen=True)
class Scope:
    run_id: int
    attempt: int
    revision: str
    approval: str
    workflow: WorkflowPolicy = PRODUCTION

    @property
    def invocation(self):
        return f"https://github.com/{ops.REPO}/actions/runs/{self.run_id}/attempts/{self.attempt}"

    def name(self, kind):
        return f"{self.workflow.artifact_prefix}-{kind}-{self.run_id}-{self.attempt}"

    def record(self):
        common = {"schema": 1, "repository_id": ops.REPO_ID, "run_id": self.run_id,
                  "attempt": self.attempt, "revision": self.revision, "approval_sha256": self.approval}
        if self.workflow == PRODUCTION:
            return {**common, "source": preview.SOURCE, "tag": preview.TAG, "draft_id": preview.DRAFT_ID}
        return {**common, "synthetic_workflow": self.workflow.caller}


def active_run(scope, *, starting=False):
    run = ops.api(f"actions/runs/{scope.run_id}", timeout=10)
    policy = scope.workflow
    ops.require(run.get("id") == scope.run_id and run.get("run_attempt") == scope.attempt
                and run.get("event") == policy.event and run.get("head_branch") == policy.branch
                and run.get("head_sha") == scope.revision
                and run.get("path") in (policy.caller, f"{policy.caller}@{policy.branch}",
                                        f"{policy.caller}@{policy.ref}")
                and (policy.title is None or run.get("display_title") == policy.title)
                and run.get("head_repository", {}).get("id") == ops.REPO_ID
                and (policy != PRODUCTION or preview.approved_actor(run.get("actor", {}).get("login", ""))),
                "HANDOFF_ACTIVE_RUN")
    status = run.get("status")
    if status == "in_progress":
        return True
    ops.require(starting and status in ("queued", "pending", "requested"), "HANDOFF_ACTIVE_RUN_STATUS")
    return None


def certificate_claims(scope, workflow, certificate):
    expected = {
        "issuer": OIDC_ISSUER, "buildSignerDigest": scope.revision,
        "sourceRepositoryIdentifier": str(ops.REPO_ID), "sourceRepositoryDigest": scope.revision,
        "sourceRepositoryRef": scope.workflow.ref, "buildConfigDigest": scope.revision,
        "buildTrigger": scope.workflow.event, "runInvocationURI": scope.invocation,
        "runnerEnvironment": "github-hosted",
    }
    def workflow_uri(path, value):
        prefix = f"https://github.com/{ops.REPO}/{path}@"
        return value in {prefix + scope.workflow.ref, prefix + scope.revision}
    ops.require(all(certificate.get(k) == v for k, v in expected.items())
                and workflow_uri(workflow, certificate.get("buildSignerURI"))
                and workflow_uri(scope.workflow.caller, certificate.get("buildConfigURI")),
                "HANDOFF_ATTESTATION_ORIGIN")


def verified_subject(result, scope, kind, sha):
    ops.require(isinstance(result, list) and len(result) == 1, "HANDOFF_ATTESTATION_COUNT")
    verified = result[0]["verificationResult"]
    certificate_claims(scope, scope.workflow.signer_for(kind), verified["signature"]["certificate"])
    statement = verified["statement"]
    subject = statement["subject"]
    ops.require(statement["predicateType"] == PREDICATE and statement["predicate"] == {"role": kind}
                and len(subject) == 1 and subject[0]["digest"] == {"sha256": sha},
                "HANDOFF_ATTESTATION_SUBJECT")


def verify_proof(scope, kind, folder, tools):
    subject, _, _ = TRANSPORT[kind]
    workflow = scope.workflow.signer_for(kind)
    body = folder / subject
    bundle = folder / "proof.json"
    verified = ops.command([tools / "gh", "attestation", "verify", body, "--bundle", bundle,
                            "--repo", ops.REPO, "--signer-workflow", f"{ops.REPO}/{workflow}",
                            "--signer-digest", scope.revision, "--source-digest", scope.revision,
                            "--source-ref", scope.workflow.ref, "--deny-self-hosted-runners",
                            "--cert-oidc-issuer", OIDC_ISSUER, "--predicate-type", PREDICATE,
                            "--format", "json"], timeout=90)
    result = json.loads(verified.stdout)
    verified_subject(result, scope, kind, ops.file_sha(body))
    return result


def matching_artifact(scope, kind, listing):
    items = listing["artifacts"]
    ops.require(listing["total_count"] == len(items) and len(items) <= 1, "HANDOFF_ARTIFACT_AMBIGUOUS")
    if not items:
        return None
    item = items[0]
    run = item.get("workflow_run", {})
    ops.require(item.get("name") == scope.name(kind) and type(item.get("id")) is int and item["id"] > 0
                and item.get("expired") is False and 0 < item.get("size_in_bytes", 0) <= TRANSPORT[kind][2]
                and re.fullmatch(r"sha256:[0-9a-f]{64}", item.get("digest", ""))
                and run.get("id") == scope.run_id and run.get("repository_id") == ops.REPO_ID
                and run.get("head_repository_id") == ops.REPO_ID
                and run.get("head_branch") == scope.workflow.branch and run.get("head_sha") == scope.revision,
                "HANDOFF_ARTIFACT_ORIGIN")
    return item


def select_job(scope, listing, name):
    jobs = listing["jobs"]
    ops.require(listing["total_count"] == len(jobs) <= 100, "HANDOFF_JOBS_INCOMPLETE")
    selected = [job for job in jobs if job.get("name") == name]
    ops.require(len(selected) <= 1, "HANDOFF_JOB_AMBIGUOUS")
    if not selected:
        return None
    job = selected[0]
    ops.require(type(job.get("id")) is int and job["id"] > 0
                and job.get("run_id") == scope.run_id and job.get("head_sha") == scope.revision
                and job.get("run_attempt", scope.attempt) == scope.attempt, "HANDOFF_JOB_ORIGIN")
    return job


def acceptance_job(scope, listing, name=None):
    job = select_job(scope, listing, name or scope.workflow.accept_job)
    if job is None:
        return False
    if job.get("status") == "completed":
        ops.require(job.get("conclusion") == "success", "HANDOFF_ACCEPTANCE_FAILED")
        return True
    ops.require(job.get("status") in ("queued", "in_progress", "waiting", "pending", "requested")
                and job.get("conclusion") is None, "HANDOFF_ACCEPTANCE_STATE")
    return False


def jobs_for_attempt(scope):
    return ops.api(f"actions/runs/{scope.run_id}/attempts/{scope.attempt}/jobs?per_page=100", timeout=10)


def accepted(scope):
    return acceptance_job(scope, jobs_for_attempt(scope))


def publisher_running(scope):
    job = select_job(scope, jobs_for_attempt(scope), scope.workflow.publish_job)
    if job is None:
        return False
    ops.require(job.get("status") != "completed" and job.get("conclusion") is None, "HANDOFF_PUBLISHER_EXITED")
    return job.get("status") == "in_progress"


def wait_for_transport(scope, kind, seconds):
    deadline = time.monotonic() + seconds
    query = urlencode({"name": scope.name(kind), "per_page": 2})
    while time.monotonic() < deadline:
        active_run(scope)
        ready = publisher_running(scope) if kind == "recipient" else accepted(scope)
        if ready:
            listing = ops.api(f"actions/runs/{scope.run_id}/artifacts?{query}", timeout=10)
            item = matching_artifact(scope, kind, listing)
            if item is not None and time.monotonic() < deadline:
                return item
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(20 if scope.workflow == PRODUCTION else 5, remaining))
    raise ops.ReleaseError("HANDOFF_DEADLINE")


def unpack_exact(archive_path, target, files):
    with ops.checked_archive(archive_path) as archive:
        entries = archive.infolist()
        ops.require(len(entries) == len(files) and {entry.filename for entry in entries} == set(files)
                    and all(0 < entry.file_size <= files[entry.filename] and not entry.is_dir()
                            for entry in entries), "HANDOFF_ARCHIVE_FILES")
        target.mkdir(mode=0o700)
        for entry in entries:
            with archive.open(entry) as source, (target / entry.filename).open("xb") as destination:
                shutil.copyfileobj(source, destination)


def fetch_wire(scope, kind, item, folder):
    current = ops.api(f"actions/artifacts/{item['id']}", timeout=10)
    matching_artifact(scope, kind, {"total_count": 1, "artifacts": [current]})
    ops.require((current["id"], current["digest"], current["size_in_bytes"]) ==
                (item["id"], item["digest"], item["size_in_bytes"]), "HANDOFF_ARTIFACT_CHANGED")
    subject, payload_limit, wire_limit = TRANSPORT[kind]
    with tempfile.TemporaryDirectory(dir=folder.parent) as tmp:
        zipped = Path(tmp) / "wire.zip"
        ops.download_api(f"actions/artifacts/{item['id']}/zip", zipped, item["digest"], limit=wire_limit)
        ops.require(zipped.stat().st_size == item["size_in_bytes"], "HANDOFF_ARCHIVE_SIZE")
        files = {subject: payload_limit}
        if kind not in ("input-receipt", "input-ciphertext"):
            files["proof.json"] = PROOF_BYTES
        unpack_exact(zipped, folder, files)


def recipient_value(scope, path):
    record = json.loads(path.read_bytes())
    recipient = record.get("recipient", "")
    ops.require(isinstance(recipient, str) and re.fullmatch(RECIPIENT_PATTERN, recipient)
                and record == {**scope.record(), "recipient": recipient}, "HANDOFF_RECIPIENT_SCOPE")
    return recipient


def create_recipient(scope, stage, tools):
    identity = stage / "identity.txt"
    ops.require(not identity.exists(), "HANDOFF_IDENTITY_EXISTS")
    ops.command([tools / "age-keygen", "-o", identity], timeout=20)
    ops.require(identity.is_file() and not identity.is_symlink() and
                stat.S_IMODE(identity.stat().st_mode) == 0o600, "HANDOFF_IDENTITY_PERMISSIONS")
    public = ops.command([tools / "age-keygen", "-y", identity], timeout=20).stdout.decode().strip()
    ops.require(re.fullmatch(RECIPIENT_PATTERN, public), "HANDOFF_RECIPIENT_FORMAT")
    wire = stage / "recipient"
    wire.mkdir(mode=0o700)
    ops.write_json(wire / "recipient.json", {**scope.record(), "recipient": public})


def receipt(scope, recipient_path):
    return {**scope.record(), "recipient_sha256": ops.file_sha(recipient_path)}


def encrypt_files(scope, recipient_path, source, files, destination, tools, *, limit=MAX_CIPHERTEXT):
    recipient = recipient_value(scope, recipient_path)
    ops.require({p.name for p in source.iterdir()} == set(files) and
                all(p.is_file() and not p.is_symlink() and 0 < p.stat().st_size <= files[p.name]
                    for p in source.iterdir()), "HANDOFF_LOCAL_FILES")
    with tempfile.TemporaryDirectory(dir=destination.parent) as tmp:
        packed = Path(tmp) / "accepted.zip"
        with ZipFile(packed, "x") as archive:
            for name in sorted(files):
                archive.write(source / name, name)
            archive.writestr(RECEIPT, json.dumps(receipt(scope, recipient_path)))
        ops.require(packed.stat().st_size < limit - 1024 * 1024, "HANDOFF_PLAINTEXT_SIZE")
        ops.command([tools / "age", "-r", recipient, "-o", destination, packed], timeout=90)
        ops.require(0 < destination.stat().st_size <= limit, "HANDOFF_CIPHERTEXT_SIZE")


def decrypt_files(scope, recipient_path, identity, ciphertext, target, files, tools, *, limit=MAX_CIPHERTEXT):
    ops.require(not identity.is_symlink() and stat.S_IMODE(identity.stat().st_mode) == 0o600
                and 0 < ciphertext.stat().st_size <= limit, "HANDOFF_DECRYPT_INPUT")
    current = ops.command([tools / "age-keygen", "-y", identity], timeout=20).stdout.decode().strip()
    ops.require(current == recipient_value(scope, recipient_path), "HANDOFF_RECIPIENT_KEY_CHANGED")
    with tempfile.TemporaryDirectory(dir=target.parent) as tmp:
        packed = Path(tmp) / "accepted.zip"
        ops.command([tools / "age", "-d", "-i", identity, "-o", packed, ciphertext], timeout=90)
        unpack_exact(packed, target, {**files, RECEIPT: RECEIPT_BYTES})
        proof = target / RECEIPT
        ops.require(json.loads(proof.read_bytes()) == receipt(scope, recipient_path), "HANDOFF_RECEIPT_SCOPE")
        proof.unlink()


def run(args):
    revision = preview.authority("publish")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    approval = os.environ.get("PREVIEW_APPROVAL_SHA256", "")
    ops.require(re.fullmatch(r"[1-9][0-9]{0,19}", run_id) and preview.hex_digest(approval), "HANDOFF_SCOPE")
    scope = Scope(int(run_id), int(os.environ["GITHUB_RUN_ATTEMPT"]), revision, approval)
    active_run(scope)
    stage = args.stage
    tools = stage / "tools"
    recipient = stage / "recipient" / "recipient.json"
    files = {preview.APK: MAX_CIPHERTEXT - 2 * 1024 * 1024,
             "release-provenance.json": 128 * 1024, "SHA256SUMS.txt": RECEIPT_BYTES}
    outgoing = stage / "ciphertext"
    if args.operation == "recipient":
        draft, _ = preview.exact_release()
        ops.require(draft["assets"] == [], "PREVIEW_DRAFT_NOT_EMPTY")
        create_recipient(scope, stage, tools)
    elif args.operation == "await-recipient":
        item = wait_for_transport(scope, "recipient", 240)
        fetch_wire(scope, "recipient", item, recipient.parent)
        verify_proof(scope, "recipient", recipient.parent, tools)
        recipient_value(scope, recipient)
        ops.require(publisher_running(scope), "HANDOFF_PUBLISHER_UNAVAILABLE")
    elif args.operation == "encrypt":
        ops.require(publisher_running(scope), "HANDOFF_PUBLISHER_UNAVAILABLE")
        verify_proof(scope, "recipient", recipient.parent, tools)
        signed = stage / "signed"
        record = preview.signed_record(signed, accepted=True)
        ops.require(record["trusted_revision"] == revision and record["approval_sha256"] == approval,
                    "HANDOFF_SIGNED_ACCEPTANCE")
        outgoing.mkdir(mode=0o700)
        encrypt_files(scope, recipient, signed, files, outgoing / "accepted.age", tools)
    else:
        item = wait_for_transport(scope, "ciphertext", 43 * 60)
        fetch_wire(scope, "ciphertext", item, outgoing)
        verify_proof(scope, "ciphertext", outgoing, tools)
        with tempfile.TemporaryDirectory(dir=stage) as tmp:
            local = Path(tmp) / "verified"
            decrypt_files(scope, recipient, stage / "identity.txt", outgoing / "accepted.age", local, files, tools)
            active_run(scope)
            ops.require(accepted(scope) and preview.authority("publish") == revision,
                        "HANDOFF_PUBLICATION_GATE")
            preview.publish(local, revision, approval)


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    try:
        parser = argparse.ArgumentParser()
        parser.add_argument("operation", choices=("recipient", "await-recipient", "encrypt", "receive"))
        parser.add_argument("--stage", required=True, type=Path)
        run(parser.parse_args())
    except ops.ReleaseError as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("FAIL: HANDOFF_OPERATION", file=sys.stderr)
        raise SystemExit(1)
