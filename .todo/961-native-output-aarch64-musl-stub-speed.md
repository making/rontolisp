# `--native` on Linux aarch64: measure the musl stub against static glibc on hardware

Difficulty: Low

The Linux stubs link musl statically (`.kb/native-output.md`, Traps, "musl"). On x86_64
the stub brings its own `memcpy`/`memmove`/`memset` and matches or beats static glibc;
aarch64 keeps musl's routines (Arm optimized-routines `memcpy`/`memset`, C `memmove`) and
was only run under `qemu-aarch64`, never timed: no aarch64 Linux machine was at hand.

## Plan

- On an aarch64 Linux machine (a GitHub `ubuntu-24.04-arm` runner is Neoverse N2), build
  the stub both ways: `build.sh --stub linux-aarch64` (musl) and the same cargo build for
  `aarch64-unknown-linux-gnu` with `-C target-feature=+crt-static -C relocation-model=static`.
- Pack the bench programs and `gc.lisp` with each (`rlpack`) and compare user cycles pinned
  to one core, interleaved, as the x86_64 numbers were.
- Equal: record the numbers in the `.kb` Traps entry. Slower: find the symbol with
  `perf record`; if it is `memmove` (overlapping copies go through musl's C loop) or
  `memcpy`, give aarch64 its own routines beside `runner/src/memfns.rs`, tested the same way.
