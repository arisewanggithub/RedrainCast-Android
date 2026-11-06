#!/usr/bin/env python3
"""Produce a path-scrubbed, same-build LGPL application relink kit."""
import argparse
import gzip
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

spec = importlib.util.spec_from_file_location("relink", Path(__file__).with_name("release-relink.py"))
relink = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relink)
guard_spec = importlib.util.spec_from_file_location("guard", Path(__file__).with_name("release-guard.py"))
guard = importlib.util.module_from_spec(guard_spec)
guard_spec.loader.exec_module(guard)
ASSETS = {
    "ffmpeg-pcm-android-7.1.3-r1.aar":
        "6157824cb3f8238a3879b3d22ddf6cc2be61abb26f2cb6ac742131f2abde462b",
    "ffmpeg-7.1.3.tar.xz":
        "f0bf043299db9e3caacb435a712fc541fbb07df613c4b893e8b77e67baf3adbe",
    "ffmpeg-pcm-android-7.1.3-r1-build-materials.tar.gz":
        "be3c282119612d8f8ff3c4f01c40e40b8d151d4519efd9a53a0fb5b50e8bdfbb",
    "ffmpeg-pcm-android-7.1.3-r1-compliance.tar.gz":
        "c4eb05e081ed9e505ed6ef58e6d0109643bed5d8afaadd71d3dfd1ba9d162304",
}
BASE = ("https://github.com/arisewanggithub/RedrainCast-Native/releases/download/"
        "ffmpeg-pcm-android-7.1.3-r1/")


def link_metadata(ninja):
    text = ninja.read_text()
    matches = re.findall(
        r"^build [^\n]+: CXX_SHARED_LIBRARY_LINKER__screencopy_resampler_[^\n]+\n((?:  .*\n)*)",
        text, re.M)
    if len(matches) != 1:
        raise ValueError("Expected one resampler link rule")
    values = dict(re.findall(r"^  (\w+) = (.*)$", matches[0], re.M))
    flags = relink.safe_flags(values["LANGUAGE_COMPILE_FLAGS"] + " " +
                              values.get("ARCH_FLAGS", "") + " " + values["LINK_FLAGS"])
    required = {"-static-libstdc++", "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384",
                "-Wl,--exclude-libs,ALL"}
    if not required.issubset(flags):
        raise ValueError("Native link contract changed; review the relink producer")
    libraries = values["LINK_LIBRARIES"]
    # Only the two public archives may have paths. Everything else must be -l.
    remainder = re.sub(r'(?:[^\s"]+|"[^"]+")lib(?:swresample|avutil)\.a"?', "", libraries)
    system = remainder.split()
    if not system or any(not re.fullmatch(r"-l(?:m|atomic)", token) for token in system):
        raise ValueError("Unexpected native dependencies; review the relink producer")
    if libraries.count("libswresample.a") != 1 or libraries.count("libavutil.a") != 1:
        raise ValueError("Expected exactly the two LGPL archives")
    if libraries.index("libswresample.a") > libraries.index("libavutil.a"):
        raise ValueError("Unexpected static archive order")
    return values, flags, system


def privacy_check(data):
    if re.search(rb"(?:(?<![A-Za-z0-9])[A-Za-z]:[\\/]|/(?:home|Users|mnt|runner|workspace)/|"
                 rb"github_pat_|gh[pousr]_[A-Za-z0-9]{20})", data):
        raise ValueError("Private path or credential-shaped content in application object/metadata")


def collect_object(repo, host, abi, shipped, destination, scratch):
    candidates = []
    for ninja in sorted((repo / "app" / ".cxx" / "RelWithDebInfo").glob("*/" + abi + "/build.ninja")):
        values, flags, system = link_metadata(ninja)
        original = Path(values["TARGET_FILE"])
        if not original.is_absolute():
            original = ninja.parent / original
        obj = ninja.parent / "CMakeFiles" / "screencopy_resampler.dir" / "resampler_jni.cpp.o"
        if not original.is_file() or not obj.is_file():
            continue
        stripped = scratch / "stripped.so"
        relink.run(relink.tool(host, "llvm-strip"), "--strip-unneeded", "-o", stripped, original)
        if relink.digest(stripped) != relink.digest(shipped):
            continue
        clean = scratch / "object.o"
        relink.run(relink.tool(host, "llvm-objcopy"), "--strip-debug", obj, clean)
        privacy_check(clean.read_bytes())
        candidate = (relink.digest(clean), flags, system)
        if candidate not in candidates:
            candidates.append(candidate)
            shutil.copyfile(clean, destination)
    if len(candidates) != 1:
        raise ValueError("Need one unambiguous object matching the exact APK ELF: " + abi)
    sha, flags, system = candidates[0]
    return {"object_sha256": sha, "flags": flags, "system_libraries": system,
            "apk_library_sha256": relink.digest(shipped),
            "dynamic_symbols": relink.elf_info(host, shipped, abi)}


def build(args):
    guard.is_prerelease(args.version)
    repo, output, cache = args.repo.resolve(), args.output.resolve(), args.cache.resolve()
    if output.exists():
        raise ValueError("Output must not already exist")
    cache.mkdir(parents=True, exist_ok=True)
    for name, sha in ASSETS.items():
        asset = cache / name
        if not asset.exists():
            urllib.request.urlretrieve(BASE + name, asset)
        if relink.digest(asset) != sha:
            raise ValueError("Pinned public asset hash mismatch: " + name)
    host = relink.toolchain(args.ndk.resolve())
    with tempfile.TemporaryDirectory(prefix="redraincast-relink-") as temporary:
        scratch = Path(temporary)
        root = scratch / "android-lgpl-relink"
        root.mkdir()
        materials = root / "materials"
        materials.mkdir()
        for name in ASSETS:
            shutil.copyfile(cache / name, materials / name)
        shutil.copyfile(repo / "tools" / "release-relink.py", root / "relink.py")
        shutil.copyfile(repo / "tools" / "release-kit-README.md", root / "README.md")
        metadata = {
            "format": 1, "version": args.version, "apk_sha256": relink.digest(args.apk),
            "source_commit": relink.run("git", "-C", repo, "rev-parse", "HEAD").strip(),
            "jni_source_sha256": relink.digest(repo / "app/src/main/cpp/resampler_jni.cpp"),
            "ndk": relink.NDK_VERSION, "min_api": 30, "assets": ASSETS, "abis": {},
        }
        with zipfile.ZipFile(args.apk) as apk, zipfile.ZipFile(cache / next(iter(ASSETS))) as aar:
            native = [n for n in apk.namelist() if n.startswith("lib/") and n.endswith("/" + relink.LIBRARY)]
            expected = {"lib/" + abi + "/" + relink.LIBRARY for abi in relink.ABIS}
            if set(native) != expected:
                raise ValueError("APK must contain exactly the three supported resampler ABIs")
            for abi in relink.ABIS:
                objdir = root / "objects" / abi
                objdir.mkdir(parents=True)
                shipped = scratch / (abi + ".so")
                shipped.write_bytes(apk.read("lib/" + abi + "/" + relink.LIBRARY))
                metadata["abis"][abi] = collect_object(
                    repo, host, abi, shipped, objdir / "resampler_jni.cpp.o", scratch)
                for module in ("swresample", "avutil"):
                    libdir = root / "ffmpeg" / abi / "lib"
                    libdir.mkdir(parents=True, exist_ok=True)
                    name = "lib" + module + ".a"
                    member = "prefab/modules/" + module + "/libs/android." + abi + "/" + name
                    (libdir / name).write_bytes(aar.read(member))
        manifest = json.dumps(metadata, indent=2, sort_keys=True) + "\n"
        privacy_check(manifest.encode())
        (root / "manifest.json").write_text(manifest, encoding="utf-8")
        relink.relink(root, args.ndk.resolve(), root / "ffmpeg", scratch / "smoke")
        files = sorted(p for p in root.rglob("*") if p.is_file())
        sums = "".join(relink.digest(p) + "  " + p.relative_to(root).as_posix() + "\n" for p in files)
        (root / "SHA256SUMS").write_text(sums, encoding="utf-8")
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("xb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
                for path in sorted(root.rglob("*")):
                    if path.is_file():
                        info = archive.gettarinfo(str(path), arcname="android-lgpl-relink/" + path.relative_to(root).as_posix())
                        info.uid = info.gid = info.mtime = 0
                        info.uname = info.gname = ""
                        info.mode = 0o644
                        with path.open("rb") as stream:
                            archive.addfile(info, stream)
    print("Three-ABI LGPL relink kit verified; SHA-256: " + relink.digest(output))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    build(parser.parse_args())
