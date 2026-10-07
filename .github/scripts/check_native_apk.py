#!/usr/bin/env python3
"""Inspect the packaged ELF64 libraries, not source or intermediate directories."""

import argparse
import struct
import zipfile

PAGE_SIZE = 16384


def load_alignments(data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("expected a little-endian ELF64 library")
    header_offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56:
        raise ValueError("invalid ELF program-header size")
    alignments = []
    for index in range(count):
        kind, _, offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, header_offset + index * entry_size
        )
        if kind == 1:  # PT_LOAD
            if alignment < PAGE_SIZE or alignment & (alignment - 1) or (address - offset) % PAGE_SIZE:
                alignments.append((alignment, False))
            else:
                alignments.append((alignment, True))
    if not alignments:
        raise ValueError("no ELF LOAD segments")
    return alignments


def audit(apk, abi, allowed):
    failures = []
    seen_allowed = set()
    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as raw:
        libraries = sorted(entry for entry in archive.namelist() if entry.startswith("lib/") and entry.endswith(".so"))
        if not libraries:
            raise ValueError("APK contains no native libraries")
        for name in libraries:
            entry = archive.getinfo(name)
            if name.split("/")[1] != abi:
                failures.append(f"unexpected ABI: {name}")
            segments = load_alignments(archive.read(entry))
            elf_ok = all(ok for _, ok in segments)
            raw.seek(entry.header_offset)
            header = raw.read(30)
            name_length, extra_length = struct.unpack_from("<HH", header, 26)
            data_offset = entry.header_offset + 30 + name_length + extra_length
            zip_ok = entry.compress_type != zipfile.ZIP_STORED or data_offset % PAGE_SIZE == 0
            print(f"{name}: LOAD alignments={[alignment for alignment, _ in segments]}, ZIP={'OK' if zip_ok else 'UNALIGNED'}, ELF={'OK' if elf_ok else 'UNALIGNED'}")
            if not zip_ok:
                failures.append(f"16 KB ZIP alignment failed: {name}")
            if not elf_ok:
                if name in allowed:
                    seen_allowed.add(name)
                    print(f"  KNOWN INCOMPATIBILITY: {name} (follow-up required)")
                else:
                    failures.append(f"16 KB ELF alignment failed: {name}")
        # Remove exceptions when the library is fixed or disappears; never silently accumulate debt.
        failures.extend(f"unused incompatibility exception: {name}" for name in sorted(set(allowed) - seen_allowed))
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--abi", required=True)
    parser.add_argument("--allow-unaligned", action="append", default=[])
    args = parser.parse_args()
    try:
        failures = audit(args.apk, args.abi, args.allow_unaligned)
    except (ValueError, struct.error, zipfile.BadZipFile) as error:
        parser.exit(1, f"Invalid APK/native library: {error}\n")
    if failures:
        parser.exit(1, "\n".join(failures) + "\n")


if __name__ == "__main__":
    main()
