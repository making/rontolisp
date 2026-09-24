# `--native`: a portable CPU baseline and a `--native-target` for other platforms

Difficulty: Medium

Depends on .todo/943 (945, done, shipped the per-platform pairs: see the end). The precompile uses Cranelift's
host detection, so an output built on one x86_64 machine may use CPU features (e.g.
AVX2) another lacks, and the stub refuses or faults there.

## What is needed

- Default to a documented baseline per architecture (x86-64-v2? v1? measure the cost on
  the ci corpus and the bench programs) with an opt-in `--native-cpu=host`.
- `--native-target <os>-<arch>`: precompile for another triple (shim built with
  Cranelift's `all-arch`; measure its size cost) and use that platform's stub from the
  bundled resources.
- Test: an output built with the baseline runs on the oldest CI runner; a cross-target
  output's header names the requested triple.

## Where 945 left the packaging (2026-09-24)

- Each `-Pnative` binary carries its HOST pair only; the release exec jar carries
  linux-x86_64, linux-aarch64 and macos-aarch64 (`.kb/native-output.md`, "Packaging").
  A `--native-target` in a native binary therefore needs the other platforms' stubs added
  to it (static Linux stubs: 3.0 / 2.4 MB raw, 1.24 / 1.08 MB gz; native-image stores
  resources uncompressed) -- or it is a jar-only feature. Decide with the `all-arch`
  shim size in hand.
- The Linux stub is built with an explicit `--target <arch>-unknown-linux-gnu`
  (static glibc). Cross-building aarch64 from x86_64 worked with `gcc-aarch64-linux-gnu`:
  `CC_aarch64_unknown_linux_gnu` and `CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER` set
  to `aarch64-linux-gnu-gcc`; the output ran under `qemu-aarch64-static`.
