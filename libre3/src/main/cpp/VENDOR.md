# Vendored native sources — provenance

## Upstream
- **Project:** Juggluco — https://github.com/j-kaltes/Juggluco
- **Author:** Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>
- **Licence:** GPL-3.0-or-later (per-file headers retained verbatim — do not strip them)
- **Branch:** `primary`
- **Vendored:** 2026-09-07
- **Paths taken:** `Common/src/main/cpp/libre3/process/**` (58 files), `Common/src/main/cpp/bcrypt/**` (5 files)
- **Bytes:** 1,970,127

AAPS is AGPL-3.0; GPL-3.0 combines into it. This is a private single-device build and is not distributed.

## Layout
Mirrors Juggluco's own `Common/src/main/cpp/` layout **deliberately**, so a future re-sync against
upstream is a straight path-for-path copy with no renaming:

```
cpp/libre3/process/    whitebox authorization engine, P-256, challenge cipher  → libl3core.a
cpp/bcrypt/            TinyCrypt AES-CCM (standard, auditable)                 → libl3ccm.a
```

Removed on vendoring: `bcrypt/*.d`, `bcrypt/Makefile` (upstream build artefacts, not sources).

## Build profile — `L3_EXTERNAL_ENTROPY_ONLY=1`

Upstream's default profile uses libcrypto **solely** as an entropy source — the whole OpenSSL
dependency is the single symbol `RAND_bytes` (see `openssl_required_symbols.inc`), reached via a
`dlopen` shim. We compile with `-DL3_EXTERNAL_ENTROPY_ONLY=1`, which:

- excludes `<openssl/rand.h>` and the redirect headers at the source level (`libre3_app_core.c`)
- makes `openssl_symbols.c` / `openssl_loader.cpp` uncompiled (excluded in `CMakeLists.txt`)
- routes all entropy through `l3_security_engine_config.entropy` / `.entropy_user`, which the JNI
  layer fills from Java `SecureRandom`

Net effect: **no `dlopen` of system libcrypto** (fragile and increasingly restricted on modern
Android), and one fewer runtime dependency.

## Verified build state (2026-09-07)

Toolchain: NDK `28.2.13676358`, CMake `3.22.1`, `arm64-v8a`, `android-31` (matches `Versions.minSdk`).

- configure + build: **clean, 0 errors, 0 warnings**
- `libl3core.a` — 1,874,860 bytes, 23 objects
- `libl3ccm.a` — 33,476 bytes
- All 12 entry points present and `T`-defined:
  `l3_sensor_security_context_{create,destroy,init,clear}` and
  `l3_sensor_security_{begin_handshake, select_app_key_and_saved_authorization,
  set_patch_certificate, create_ephemeral_public_key_into, derive_authorization_root,
  encrypt_challenge_reply_into, decrypt_challenge_response_into, export_saved_authorization_into}`
- **Only unresolved externals are libc:**
  `calloc free malloc memcmp memcpy __memcpy_chk __memset_chk __stack_chk_fail`
- No `RAND_bytes`, no `dlopen`, no `dlsym` anywhere in the archive.

Reproduce:
```sh
NDK=$SDK/ndk/28.2.13676358; CM=$SDK/cmake/3.22.1/bin
$CM/cmake -S <this dir> -B <build> -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DCMAKE_MAKE_PROGRAM=$CM/ninja
$CM/cmake --build <build>
```

## Our code in this tree (NOT vendored)

- `jni/libre3_jni.cpp` — the JNI shim. Thin argument marshalling over
  `sensor_security_context.h`, plus the AES-128-CCM session cipher
  (13-byte nonce, 4-byte tag, 8 packet descriptors) reimplemented rather than
  vendoring Juggluco's `bcrypt.cpp`, so no vendored file needs editing.
- `CMakeLists.txt` — builds `libl3core.a` + `libl3ccm.a` and links them into
  `liblibre3.so`.

Two behaviours are inherited from Juggluco deliberately and must not be
"tidied up": `freeSecurityContext` and `freeSessionCipher` are **no-ops**.
Juggluco saw a SIGBUS at sensor termination with the faulting address equal to
the context pointer — Android delivers queued BLE GATT callbacks while `free()`
is unwinding. Both allocations are small and fixed-size; leaking them for
process lifetime is the cheaper trade.

Also note the return convention: the int-returning handshake calls signal
**success as 1**, not 0. `L3_SECURITY_OK` (0) is an internal status enum.

### Shared library state (2026-09-07)

`liblibre3.so`, arm64-v8a, built clean (0 errors, 0 warnings):
- 13 `Java_app_aaps_libre3_Libre3Native_*` entry points exported
- **0 vendored `l3_*` symbols in `.dynsym`** — `-fvisibility=hidden` on all three
  targets keeps the core internal (JNIEXPORT is `visibility("default")`, so the
  JNI entry points survive). Without this ~350 internal symbols leak, which is
  pointless surface and makes the vendor provenance trivially greppable.
- stripped: 640,000 bytes
- `NEEDED`: `liblog.so`, `libm.so`, `libdl.so`, `libc.so` — system only

## Rules for this directory

1. **Do not hand-edit vendored files.** Fixes go upstream or into a clearly-marked patch file so
   re-syncs stay mechanical.
2. **Keep the GPL headers.**
3. `fixed_app_private_keys.c` is vendored **verbatim from the public GPL-3.0 Juggluco repo**
   (`j-kaltes/Juggluco`, branch `primary`), where it has been published for years — committing it
   here (with its GPL header intact) exposes nothing new and is exactly what the licence
   contemplates. What must **never** be committed is *per-sensor credentials* — the BLE PIN / kAuth
   of an activated sensor; those live only in `realdata/` (outside the repo) and the app's private
   files dir, and are excluded by `.gitignore`. Distributing a working **APK** built from this tree
   is a separate redistribution / medical-device question — this is a private single-device build.
4. This code gates **authentication and transport only**. Glucose arrives pre-calibrated in mg/dL
   from the patch; nothing here computes a BG. Keep it that way — the range/rate sanity gate at the
   DB boundary is the backstop, not this code.
