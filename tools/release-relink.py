#!/usr/bin/env python3
"""Relink the supplied application object with original or modified LGPL archives."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ABIS = {
    "arm64-v8a": ("aarch64-none-linux-android30", "AArch64"),
    "armeabi-v7a": ("armv7-none-linux-androideabi30", "ARM"),
    "x86_64": ("x86_64-none-linux-android30", "Advanced Micro Devices X86-64"),
}
NDK_VERSION = "27.2.12479018"
LIBRARY = "libscreencopy_resampler.so"


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True, stderr=subprocess.STDOUT)


def toolchain(ndk):
    properties = (ndk / "source.properties").read_text()
    if not re.search(r"Pkg.Revision\s*=\s*" + re.escape(NDK_VERSION) + r"\s*$", properties, re.M):
        raise ValueError("The exact NDK version " + NDK_VERSION + " is required")
    hosts = [p for p in (ndk / "toolchains" / "llvm" / "prebuilt").iterdir()
             if (p / "bin").is_dir()]
    if len(hosts) != 1:
        raise ValueError("Expected exactly one installed NDK host toolchain")
    return hosts[0]


def tool(host, name):
    return host / "bin" / (name + (".exe" if os.name == "nt" else ""))


def safe_flags(value):
    if not re.fullmatch(r"[-=,a-zA-Z0-9+_. ]*", value):
        raise ValueError("Nonportable or unsafe link flags")
    return value.split()


def elf_info(host, library, abi):
    header = run(tool(host, "llvm-readelf"), "-h", library)
    if not re.search(r"Machine:\s+" + re.escape(ABIS[abi][1]) + r"\s*$", header, re.M):
        raise ValueError("Wrong ELF machine for " + abi)
    program = run(tool(host, "llvm-readelf"), "-lW", library)
    loads = [line.split() for line in program.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(line[-1], 16) < 16384 for line in loads):
        raise ValueError("ELF LOAD segments must be aligned to at least 16 KiB")
    dynamic = run(tool(host, "llvm-readelf"), "-dW", library)
    if "libswresample.so" in dynamic or "libavutil.so" in dynamic or "libc++_shared.so" in dynamic:
        raise ValueError("Unexpected shared FFmpeg/C++ dependency")
    symbols = run(tool(host, "llvm-nm"), "--dynamic", "--defined-only", "--extern-only", library)
    names = sorted(line.split()[-1] for line in symbols.splitlines() if line.split())
    if not any(name.startswith("Java_") for name in names):
        raise ValueError("Missing exported JNI entry points")
    return names


def relink(root, ndk, prefix, output):
    metadata = json.loads((root / "manifest.json").read_text())
    host = toolchain(ndk)
    output.mkdir(parents=True, exist_ok=True)
    for abi, (target, _) in ABIS.items():
        entry = metadata["abis"][abi]
        obj = root / "objects" / abi / "resampler_jni.cpp.o"
        if digest(obj) != entry["object_sha256"]:
            raise ValueError("Application object checksum mismatch: " + abi)
        libraries = [prefix / abi / "lib" / ("lib" + name + ".a")
                     for name in ("swresample", "avutil")]
        if not all(p.is_file() for p in libraries):
            raise ValueError("Missing static LGPL archives for " + abi)
        destination = output / abi / LIBRARY
        destination.parent.mkdir(exist_ok=True)
        flags = safe_flags(" ".join(entry["flags"]))
        run(tool(host, "clang++"), "--target=" + target,
            "--sysroot=" + str(host / "sysroot"), "-fPIC", *flags,
            "-shared", "-Wl,-soname," + LIBRARY, "-o", destination, obj, *libraries,
            *entry["system_libraries"])
        if elf_info(host, destination, abi) != entry["dynamic_symbols"]:
            raise ValueError("Relinked JNI export set differs from shipped APK: " + abi)
        print("Relink verified: " + abi)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--ffmpeg-prefix", required=True, type=Path,
                        help="Directory containing <ABI>/lib/lib{swresample,avutil}.a")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    relink(Path(__file__).resolve().parent, args.ndk.resolve(),
           args.ffmpeg_prefix.resolve(), args.output.resolve())
