cmake_minimum_required(VERSION 3.22.1)
project(libre3 C CXX)

# Vendored from Juggluco (GPL-3.0, Jaap Korthals Altes) — see VENDOR.md.
# Layout mirrors Juggluco's Common/src/main/cpp/ so future re-syncs stay mechanical.

set(L3_PROCESS ${CMAKE_CURRENT_SOURCE_DIR}/libre3/process)
set(L3_BCRYPT  ${CMAKE_CURRENT_SOURCE_DIR}/bcrypt)

file(GLOB L3_PROCESS_SRC ${L3_PROCESS}/*.c)
# EXTERNAL_ENTROPY_ONLY profile: entropy is supplied by the caller (Java
# SecureRandom via JNI), so the libcrypto dlopen shim is not compiled at all.
# This removes the project's ONLY OpenSSL dependency (RAND_bytes).
list(FILTER L3_PROCESS_SRC EXCLUDE REGEX "openssl_symbols\\.c$")

add_library(l3core STATIC ${L3_PROCESS_SRC})
target_include_directories(l3core PUBLIC ${L3_PROCESS})
target_compile_definitions(l3core PUBLIC L3_EXTERNAL_ENTROPY_ONLY=1)
target_compile_options(l3core PRIVATE -Wall -Wno-unused-function -Wno-unused-variable)

add_library(l3ccm STATIC
    ${L3_BCRYPT}/aes_encrypt.c
    ${L3_BCRYPT}/ccm_mode.c
    ${L3_BCRYPT}/utils.c)
target_include_directories(l3ccm PUBLIC ${L3_BCRYPT})

# --- JNI shim (ours, not vendored) -----------------------------------------
add_library(libre3 SHARED ${CMAKE_CURRENT_SOURCE_DIR}/jni/libre3_jni.cpp)
target_link_libraries(libre3 PRIVATE l3core l3ccm log)
target_compile_options(libre3 PRIVATE -Wall -Wextra -Wno-unused-parameter)

# Export ONLY the JNI entry points. Without this the vendored core's ~350
# internal l3_* symbols land in .dynsym: pointless surface, and it makes the
# vendor provenance trivially greppable in a shipped APK.
# JNIEXPORT is visibility("default") in the NDK, so the Java_* functions survive.
target_compile_options(l3core PRIVATE -fvisibility=hidden)
target_compile_options(l3ccm  PRIVATE -fvisibility=hidden)
target_compile_options(libre3 PRIVATE -fvisibility=hidden)

# The whitebox tables dominate the binary and do not compress or strip usefully;
# -O2 keeps the round functions sane without inflating them further.
target_compile_options(l3core PRIVATE -O2)
