# TMessagesProj/jni — Native Build Reproducibility

F-Droid verifies reproducibility by comparing unsigned APK contents against the developer-signed release (signature files stripped). The following sources of non-determinism in native libs are addressed:

| Source | Fix | Files |
|---|---|---|
| `__FILE__` macro paths (653 strings from voip, BoringSSL, tde2e, exoplayer) | `-Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__` | `CMakeLists.txt`, `voip/CMakeLists.txt`, `build_boringssl.sh`, `build_tde2e.sh`, `build_dav1d.sh` |
| ffmpeg configure string (embeds absolute NDK path) | `sed` on `config.h` after configure | `build_ffmpeg_clang.sh` |
| libvpx configure string (embeds absolute NDK path) | `sed` on `vpx_config.*` after configure | `build_libvpx_clang.sh` |
| ffmpeg version string (shallow clones lack tags → git hash) | Replace `ffbuild/version.sh` with fixed-output script | `build_ffmpeg_clang.sh` |
| libvpx assembly (system `yasm` version varies across distros) | Prepend NDK yasm dir to `PATH` and pass `--as=yasm` (libvpx 1.14+ configure only accepts bare tool names) | `build_libvpx_clang.sh` |

**Why `-D__FILE__=__FILE_NAME__` instead of `-fmacro-prefix-map`**: NDK r21e uses clang 9.0.9, which does NOT support `-fmacro-prefix-map` (requires clang 10+). The `__FILE_NAME__` builtin (clang 9+) returns just the filename without directory path, producing identical strings regardless of build directory.
