# `--native` on Linux, and shipping the shims per platform

Difficulty: Medium

Depends on .todo/942 and .todo/943. `rontolisp-native/build.sh` builds for the host and
ran on macOS arm64 (spike) and Linux x86_64 (2026-09-24: shim 10.0 MB, stub 2.0 MB,
glibc-dynamic -- `.kb/native-output.md`).

## What is needed

- Linux x86_64 / aarch64 builds of the shim dylib (`.so`) and the stub. Build the stub
  against musl so the output has no glibc floor; check `ldd` says "statically linked".
  ELF needs no signing, so the appended payload stays as is (or reuse 944's section
  approach if it is simpler to share).
- CI: build the Rust module on each release platform (rustc >= 1.96), put the artifacts
  where the jar and each `-Pnative` binary pick them up as resources; the native-image
  legs run the 943 E2E.
- Packaging: every platform's shims in the jar costs ~7 MB each; decide between an
  all-platform jar, a separate `-native` artifact, or host-only in each native binary
  (which is per-platform already). Measure the sizes, then choose.
- Windows is out of scope until the rest ships.
