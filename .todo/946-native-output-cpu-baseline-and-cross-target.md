# `--native`: a portable CPU baseline and a `--native-target` for other platforms

Difficulty: Medium

Depends on .todo/943 (and 945 for non-host stubs). The precompile uses Cranelift's
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
