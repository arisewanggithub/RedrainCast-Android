#!/usr/bin/env python3
"""Focused release tooling tests; no Gradle, device or private credentials."""
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("kit", Path(__file__).with_name("release-kit.py"))
kit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kit)
guard_spec = importlib.util.spec_from_file_location("guard", Path(__file__).with_name("release-guard.py"))
guard = importlib.util.module_from_spec(guard_spec)
guard_spec.loader.exec_module(guard)

RULE = """build /build/libscreencopy_resampler.so: CXX_SHARED_LIBRARY_LINKER__screencopy_resampler_RelWithDebInfo object.o
  LANGUAGE_COMPILE_FLAGS = -g -O2 -DNDEBUG -march=armv7-a -mthumb
  LINK_FLAGS = -static-libstdc++ -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL
  LINK_LIBRARIES = /cache/swresample/libswresample.a /cache/avutil/libavutil.a -lm -lm -latomic -lm
  TARGET_FILE = /build/libscreencopy_resampler.so
"""


class ReleaseTests(unittest.TestCase):
    def test_release_suffix_infers_prerelease_status(self):
        for version in ("0.5.0-alpha.1", "0.5.0-beta.2", "0.5.0-rc.1"):
            with self.subTest(version=version):
                self.assertTrue(guard.is_prerelease(version))
                self.assertTrue(guard.validate_release_tag("v" + version, version))
        for version in ("0.0.0", "0.5.0", "1.10.100"):
            with self.subTest(version=version):
                self.assertFalse(guard.is_prerelease(version))
                self.assertFalse(guard.validate_release_tag("v" + version, version))

    def test_noncanonical_or_unsafe_release_versions_are_rejected(self):
        for version in ("0.5.0_pre-release", "0.5.0-rc", "0.5.0-rc.0", "0.5.0-rc.01",
                        "00.5.0", "0.05.0", "0.5.00", "0.5.0-RC.1", "v0.5.0",
                        "0.5.0+build", "0.5.0-rc.1/../../", "1" * 80 + ".0.0"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                guard.is_prerelease(version)

    def test_tag_and_embedded_apk_version_cannot_have_different_channels(self):
        for tag, apk in (("v0.5.0-rc.1", "0.5.0"), ("v0.5.0", "0.5.0-rc.1"),
                         ("v0.5.0-rc.1", "0.5.0-rc.2"), ("v0.5.0-beta.1", "0.5.0-rc.1"),
                         ("0.5.0", "0.5.0")):
            with self.subTest(tag=tag, apk=apk), self.assertRaises(ValueError):
                guard.validate_release_tag(tag, apk)

    def test_relink_kit_accepts_the_same_stable_and_prerelease_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "existing.tar.gz"
            output.write_bytes(b"existing")
            for version in ("0.5.0", "0.5.0-rc.1"):
                with self.subTest(version=version), self.assertRaisesRegex(ValueError, "Output must not already exist"):
                    kit.build(SimpleNamespace(version=version, repo=root, output=output, cache=root / "cache"))

    def test_relink_kit_rejects_bad_versions_before_download_or_filesystem_access(self):
        with self.assertRaises(ValueError):
            kit.build(SimpleNamespace(version="0.5.0_pre-release"))

    def test_sdk37_and_legacy_signer_certificates_are_accepted(self):
        digest = "a" * 64
        for label in ("Signer #1", "V2 Signer:", "V3 Signer:", "V3.1 Signer:"):
            with self.subTest(label=label):
                self.assertEqual(digest, guard.validate_certificate(
                    label + " certificate SHA-256 digest: " + digest + "\n", digest))
        report = ("V2 Signer: certificate SHA-256 digest: " + digest.upper() + "\n" +
                  "V3 Signer: certificate SHA-256 digest: " + digest + "\n")
        self.assertEqual(digest, guard.validate_certificate(report, digest))

    def test_wrong_or_additional_signer_is_rejected(self):
        report = ("V2 Signer: certificate SHA-256 digest: " + "a" * 64 + "\n" +
                  "V3 Signer: certificate SHA-256 digest: " + "b" * 64 + "\n")
        with self.assertRaises(ValueError):
            guard.validate_certificate(report, "a" * 64)
        with self.assertRaises(ValueError):
            guard.validate_certificate("Signer #1 certificate SHA-256 digest: " + "b" * 64, "a" * 64)

    def test_missing_signer_or_source_stamp_alone_is_rejected(self):
        for report in ("", "Source Stamp certificate SHA-256 digest: " + "a" * 64):
            with self.subTest(report=report), self.assertRaises(ValueError):
                guard.validate_certificate(report, "a" * 64)

    def test_invalid_approved_certificate_is_rejected(self):
        with self.assertRaises(ValueError):
            guard.validate_certificate("V2 Signer: certificate SHA-256 digest: " + "a" * 64, "short")

    def test_exact_reviewed_private_main_is_accepted(self):
        sha = "a" * 40
        environment = {
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REPOSITORY": "arisewanggithub/RedrainCast-Android",
            "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": sha, "REVIEWED_COMMIT": sha,
        }
        guard.validate_revision(environment, sha, sha)

    def test_forks_pull_requests_branches_and_unreviewed_revisions_are_rejected(self):
        sha = "a" * 40
        environment = {
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REPOSITORY": "arisewanggithub/RedrainCast-Android",
            "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": sha, "REVIEWED_COMMIT": sha,
        }
        invalid = [
            {"GITHUB_EVENT_NAME": "pull_request"},
            {"GITHUB_EVENT_NAME": "pull_request_target"},
            {"GITHUB_REPOSITORY": "another-owner/RedrainCast-Android"},
            {"GITHUB_REF": "refs/heads/unfinished"},
            {"GITHUB_REF": "refs/tags/v0.4.6"},
            {"GITHUB_SHA": "b" * 40},
            {"REVIEWED_COMMIT": ""},
            {"REVIEWED_COMMIT": "a" * 7},
            {"REVIEWED_COMMIT": "b" * 40},
        ]
        for change in invalid:
            with self.subTest(change=change), self.assertRaises(ValueError):
                guard.validate_revision(dict(environment, **change), sha, sha)
        for checkout, main in (("b" * 40, sha), (sha, "b" * 40)):
            with self.subTest(checkout=checkout, main=main), self.assertRaises(ValueError):
                guard.validate_revision(environment, checkout, main)

    def metadata(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "build.ninja"
            path.write_text(text)
            return kit.link_metadata(path)

    def test_exact_flags_and_archive_order(self):
        _, flags, system = self.metadata(RULE)
        self.assertIn("-march=armv7-a", flags)
        self.assertEqual(["-lm", "-lm", "-latomic", "-lm"], system)

    def test_windows_archive_paths_are_not_published(self):
        _, flags, system = self.metadata(RULE.replace("/cache/", "C:/cache/"))
        kit.privacy_check((" ".join(flags + system)).encode())

    def test_missing_no_undefined_rejected(self):
        with self.assertRaises(ValueError):
            self.metadata(RULE.replace("-Wl,--no-undefined ", ""))

    def test_unexpected_library_rejected(self):
        with self.assertRaises(ValueError):
            self.metadata(RULE.replace("-latomic", "-lprivate"))

    def test_archive_reorder_rejected(self):
        with self.assertRaises(ValueError):
            self.metadata(RULE.replace(
                "/cache/swresample/libswresample.a /cache/avutil/libavutil.a",
                "/cache/avutil/libavutil.a /cache/swresample/libswresample.a"))

    def test_missing_rule_rejected(self):
        with self.assertRaises(ValueError):
            self.metadata("")

    def test_flags_cannot_reference_paths_response_files_or_commands(self):
        for value in ("-L/private", "@response", "-Wl,a;echo", "$(secret)", "C:\\private"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                kit.relink.safe_flags(value)

    def test_private_paths_and_tokens_rejected(self):
        for value in (b"C:\\Users\\someone", b"/home/runner/work/private",
                      b"/Users/someone", b"github_pat_secret",
                      b"ghp_abcdefghijklmnopqrstuvwxyz"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                kit.privacy_check(value)

    def test_public_metadata_is_safe(self):
        kit.privacy_check(b'{"version":"0.4.8","source_commit":"abcdef"}')
        kit.privacy_check(b"Android clang https://android.googlesource.com/toolchain/llvm-project")

    def test_all_public_assets_are_strongly_pinned(self):
        self.assertEqual(4, len(kit.ASSETS))
        for value in kit.ASSETS.values():
            self.assertRegex(value, r"^[0-9a-f]{64}$")

    def elf(self, alignment="0x4000", machine="AArch64", dynamic=""):
        output = [
            "  Machine: " + machine + "\n",
            "  LOAD 0x0 0x0 0x0 0x123 0x123 R E " + alignment + "\n",
            dynamic,
            "0000000000000010 T Java_example_nativeEntry\n",
        ]
        with patch.object(kit.relink, "run", side_effect=output):
            return kit.relink.elf_info(Path("host"), Path("test.so"), "arm64-v8a")

    def test_exact_16k_alignment_accepted(self):
        self.assertEqual(["Java_example_nativeEntry"], self.elf())

    def test_8k_alignment_rejected(self):
        with self.assertRaises(ValueError):
            self.elf(alignment="0x2000")

    def test_wrong_elf_machine_rejected(self):
        with self.assertRaises(ValueError):
            self.elf(machine="ARM")

    def test_shared_lgpl_or_cpp_dependency_rejected(self):
        for library in ("libswresample.so", "libavutil.so", "libc++_shared.so"):
            with self.subTest(library=library), self.assertRaises(ValueError):
                self.elf(dynamic="NEEDED " + library)

    def test_relative_cmake_target_is_resolved_from_ninja_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build = root / "app" / ".cxx" / "RelWithDebInfo" / "fixture" / "arm64-v8a"
            build.mkdir(parents=True)
            (build / "build.ninja").write_text(RULE.replace(
                "TARGET_FILE = /build/libscreencopy_resampler.so",
                "TARGET_FILE = libscreencopy_resampler.so"))
            (build / "libscreencopy_resampler.so").write_bytes(b"original")
            objdir = build / "CMakeFiles" / "screencopy_resampler.dir"
            objdir.mkdir(parents=True)
            (objdir / "resampler_jni.cpp.o").write_bytes(b"object")
            shipped = root / "shipped.so"
            shipped.write_bytes(b"stripped")
            destination = root / "published.o"

            def fake_run(*args):
                if args[1] == "--strip-unneeded":
                    Path(args[3]).write_bytes(b"stripped")
                elif args[1] == "--strip-debug":
                    Path(args[-1]).write_bytes(b"object")
                else:
                    self.fail("Unexpected native command")

            with patch.object(kit.relink, "run", side_effect=fake_run), \
                    patch.object(kit.relink, "elf_info", return_value=["Java_example_nativeEntry"]):
                result = kit.collect_object(root, root, "arm64-v8a", shipped, destination, root)
            self.assertEqual(kit.relink.digest(destination), result["object_sha256"])


if __name__ == "__main__":
    unittest.main()
