import contextlib
import io
import pathlib
import struct
import tempfile
import unittest
import zipfile

from check_native_apk import audit, load_alignments


def elf(alignment):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
    return data


class NativeApkTest(unittest.TestCase):
    def test_load_segments_must_support_16kb(self):
        self.assertEqual(load_alignments(elf(4096)), [(4096, False)])
        self.assertEqual(load_alignments(elf(16384)), [(16384, True)])
        self.assertEqual(load_alignments(elf(65536)), [(65536, True)])

    def test_malformed_elf_is_not_a_pass(self):
        with self.assertRaises(ValueError):
            load_alignments(bytes(120))
        with self.assertRaises(struct.error):
            load_alignments(elf(16384)[:100])

    def test_load_offset_and_address_must_be_congruent(self):
        data = elf(16384)
        struct.pack_into("<Q", data, 80, 4096)
        self.assertEqual(load_alignments(data), [(16384, False)])

    def test_apk_inventory_and_exceptions(self):
        name = "lib/arm64-v8a/libexample.so"
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            apk = pathlib.Path(directory) / "test.apk"
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(name, elf(4096))
            self.assertIn(f"16 KB ELF alignment failed: {name}", audit(apk, "arm64-v8a", []))
            self.assertEqual(audit(apk, "arm64-v8a", [name]), [])
            self.assertIn(f"unexpected ABI: {name}", audit(apk, "x86_64", [name]))
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(name, elf(16384))
            self.assertEqual(audit(apk, "arm64-v8a", [name]), [f"unused incompatibility exception: {name}"])

    def test_zip_alignment_is_independent_of_elf_alignment(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            apk = pathlib.Path(directory) / "test.apk"
            name = "lib/arm64-v8a/libexample.so"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(name, elf(16384))
            self.assertEqual(audit(apk, "arm64-v8a", []), [f"16 KB ZIP alignment failed: {name}"])


if __name__ == "__main__":
    unittest.main()
