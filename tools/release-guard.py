#!/usr/bin/env python3
"""Require manual approval of the exact current private main revision."""
import os
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


if __name__ == "__main__":
    checkout = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    remote = subprocess.check_output(
        ["git", "ls-remote", "--exit-code", "origin", "refs/heads/main"], text=True).split()
    if len(remote) != 2 or remote[1] != "refs/heads/main":
        raise ValueError("Cannot resolve the current private main revision")
    validate_revision(os.environ, checkout, remote[0])
    print("Exact reviewed private main revision verified")
