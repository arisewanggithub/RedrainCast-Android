#!/usr/bin/env python3
"""Require the exact approved source revision and signing certificate."""
import argparse
import os
from pathlib import Path
import re
import subprocess


def validate_revision(environment, checkout, main):
    if (environment.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or
            environment.get("GITHUB_REPOSITORY") != "arisewanggithub/RedrainCast-Android" or
            environment.get("GITHUB_REF") != "refs/heads/main"):
        raise ValueError("Releases require manual dispatch from private main")
    reviewed = environment.get("REVIEWED_COMMIT", "")
    if not re.fullmatch(r"[0-9a-f]{40}", reviewed):
        raise ValueError("A full reviewed commit SHA is required")
    if any(value != reviewed for value in (environment.get("GITHUB_SHA"), checkout, main)):
        raise ValueError("Reviewed commit must match checkout, dispatch and current main")


def validate_certificate(report, expected):
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise ValueError("Invalid approved certificate SHA-256")
    digests = re.findall(
        r"^(?:Signer #\d+|V[234](?:\.\d+)? Signer):? certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        report, re.M)
    if not digests:
        raise ValueError("No recognized APK signer certificate SHA-256 in verification report")
    if {digest.lower() for digest in digests} != {expected.lower()}:
        raise ValueError("APK signer certificate does not match the approved production certificate")
    return expected.lower()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate-report", type=Path)
    args = parser.parse_args()
    if args.certificate_report:
        digest = validate_certificate(
            args.certificate_report.read_text(encoding="utf-8"), os.environ["RELEASE_CERT_SHA256"])
        print("Approved production certificate SHA-256: " + digest)
    else:
        checkout = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        remote = subprocess.check_output(
            ["git", "ls-remote", "--exit-code", "origin", "refs/heads/main"], text=True).split()
        if len(remote) != 2 or remote[1] != "refs/heads/main":
            raise ValueError("Cannot resolve the current private main revision")
        validate_revision(os.environ, checkout, remote[0])
        print("Exact reviewed private main revision verified")
