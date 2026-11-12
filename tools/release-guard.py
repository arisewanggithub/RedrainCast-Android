#!/usr/bin/env python3
"""Require the exact approved source revision and signing certificate."""
import argparse
import os
from pathlib import Path
import re
import subprocess


VERSION = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-(alpha|beta|rc)\.([1-9][0-9]*))?"
)


def is_prerelease(version):
    match = VERSION.fullmatch(version) if len(version) <= 79 else None
    if not match:
        raise ValueError("Use major.minor.patch or major.minor.patch-alpha.N/-beta.N/-rc.N")
    return match.group(4) is not None


def validate_release_tag(tag, apk_version=None):
    if not tag.startswith("v"):
        raise ValueError("Release tag must begin with v")
    prerelease = is_prerelease(tag[1:])
    if apk_version is not None and tag != "v" + apk_version:
        raise ValueError("Release tag must exactly match the embedded APK version")
    return prerelease


def validate_revision(environment, checkout, main):
    if (environment.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or
            environment.get("GITHUB_REPOSITORY") != "arisewanggithub/RedrainCast-Android" or
            environment.get("GITHUB_REF") != "refs/heads/main"):
        raise ValueError("Releases require manual dispatch from the Android repository main branch")
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
    parser.add_argument("--release-tag")
    parser.add_argument("--apk-version")
    args = parser.parse_args()
    if args.apk_version is not None and args.release_tag is None:
        parser.error("--apk-version requires --release-tag")
    if args.release_tag is not None:
        if args.certificate_report:
            parser.error("Release version and certificate checks must be invoked separately")
        print("prerelease" if validate_release_tag(args.release_tag, args.apk_version) else "stable")
    elif args.certificate_report:
        digest = validate_certificate(
            args.certificate_report.read_text(encoding="utf-8"), os.environ["RELEASE_CERT_SHA256"])
        print("Approved production certificate SHA-256: " + digest)
    else:
        checkout = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        remote = subprocess.check_output(
            ["git", "ls-remote", "--exit-code", "origin", "refs/heads/main"], text=True).split()
        if len(remote) != 2 or remote[1] != "refs/heads/main":
            raise ValueError("Cannot resolve the current Android main revision")
        validate_revision(os.environ, checkout, remote[0])
        print("Exact reviewed Android main revision verified")
