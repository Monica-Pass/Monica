#!/usr/bin/env python3
"""Verify the checked-in MDBX3 Android libraries against their provenance file."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path


EXPECTED_MACHINES = {
    "arm64-v8a": 183,  # EM_AARCH64
    "armeabi-v7a": 40,  # EM_ARM
    "x86_64": 62,  # EM_X86_64
}
LIBRARY_NAME = "libmdbx_ffi.so"


def align4(value: int) -> int:
    return (value + 3) & ~3


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_ids(data: bytes) -> list[str]:
    if data[:4] != b"\x7fELF" or len(data) < 20:
        raise ValueError("library is not a complete ELF shared object")
    byte_order = {1: "<", 2: ">"}.get(data[5])
    if byte_order is None:
        raise ValueError("ELF has an unsupported byte order")

    results: list[str] = []
    cursor = 0
    while True:
        cursor = data.find(b"GNU\x00", cursor)
        if cursor < 12:
            break
        header = cursor - 12
        try:
            namesz, descsz, note_type = struct.unpack_from(
                f"{byte_order}III", data, header
            )
        except struct.error:
            break
        name_offset = header + 12
        desc_offset = name_offset + align4(namesz)
        end = desc_offset + descsz
        if (
            namesz == 4
            and descsz == 20
            and note_type == 3
            and data[name_offset:desc_offset].startswith(b"GNU\x00")
            and end <= len(data)
        ):
            results.append(data[desc_offset:end].hex())
        cursor += 4
    return results


def verify(root: Path) -> None:
    provenance_path = root / "Monica for Android/mdbx-engine/MDBX3_RUNTIME_PROVENANCE.json"
    provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    if provenance.get("schema") != "monica-mdbx3-runtime-provenance-v1":
        raise ValueError("unexpected provenance schema")
    if provenance.get("status") != "preview":
        raise ValueError("bundled MDBX3 runtime must remain marked preview")
    runtime = provenance.get("runtime", {})
    if runtime.get("storage_format") != "MDBX-2":
        raise ValueError("MDBX3 bundled runtime must keep the MDBX-2 storage format")
    if runtime.get("native_library") != LIBRARY_NAME:
        raise ValueError("unexpected MDBX3 native library name")

    artifacts = provenance.get("artifacts", {})
    if set(artifacts) != set(EXPECTED_MACHINES):
        raise ValueError("provenance must contain exactly the three Android ABIs")

    library_root = root / "Monica for Android/mdbx-engine/src/main/jniLibs"
    for abi, expected_machine in EXPECTED_MACHINES.items():
        record = artifacts[abi]
        path = library_root / abi / LIBRARY_NAME
        if not path.is_file():
            raise ValueError(f"missing bundled library: {path}")
        data = path.read_bytes()
        if len(data) != int(record["bytes"]):
            raise ValueError(f"size mismatch for {abi}")
        actual_hash = hashlib.sha256(data).hexdigest()
        if actual_hash != record["sha256"]:
            raise ValueError(f"SHA-256 mismatch for {abi}")
        if data[:4] != b"\x7fELF":
            raise ValueError(f"{abi} is not an ELF shared object")
        machine = int.from_bytes(data[18:20], "little" if data[5] == 1 else "big")
        if machine != expected_machine:
            raise ValueError(f"{abi} has ELF machine {machine}, expected {expected_machine}")
        ids = build_ids(data)
        if ids != [record["build_id"]]:
            raise ValueError(f"GNU build-id mismatch or duplicate note for {abi}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        verify(args.root.resolve())
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"MDBX3 bundled runtime verification failed: {error}", file=sys.stderr)
        return 1
    print("MDBX3 bundled runtime verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
