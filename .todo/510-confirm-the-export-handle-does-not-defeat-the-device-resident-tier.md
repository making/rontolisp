# Confirm the jvm-export handle does not defeat the device-resident tier

Difficulty: Medium

Carved out of `.todo/504` when the packed float-array boundary type landed. **The CUDA
half is done** (2026-08-24, GB10): the claim holds, the harness is checked in, and
`.kb/jvm-export.md` / `.kb/gpu.md` carry the numbers. What is left is the SAME three
measurements on Metal, which cannot be taken here -- the lazy/resident tier was settled
separately for that backend (`.todo/494`, where lazy results are measured as a tie and
left OFF), so its answer does not follow from CUDA's.

## What was measured, and how to repeat it

`examples/jvm/bench/` -- `gpu-kernels.lisp` (one GEMV, exported per call and as a whole
chain) + `GpuResidencyBench.java`, run by `./run.sh gpu`. The bench compiles the same
source twice, with `--gpu` and without, and reads the library's own residency counters
reflectively out of the compiled class (`<package>.RontoLispGpuDeviceResidency`), which is
the compiled-class stand-in for `GpuThresholds.dirtyCount()`.

On CUDA, 200 chained GEMVs over a resident 2048x2048 f32 matrix:

1. **stays resident across the boundary** -- 0.070 ms/iteration through the handle against
   0.070 for the same chain inside Lisp, and 1 upload (8 KB) for the whole run where a
   materializing boundary pays 200 (1600 KB) and 0.098-0.117 ms/iteration;
2. **`toArray()` brings it home exactly once** -- one dirty copy cleared, one stub given a
   backing, a second read moves nothing -- and answers the no-`--gpu` build bit for bit;
3. **`set(i, v)` lands on the array the guard answers** -- both into a lazy result the
   device still holds and into the resident matrix, whose device copy the write
   invalidates, and the next kernel call sees both.

## What is left: the same three on Metal

Needs a Mac. `./run.sh gpu` is the whole procedure; the bench prints
`device: present` or a line saying the run proves nothing, so a device-less machine cannot
mistake the output for a result.

Two things to expect to differ, and to record rather than to fix:

- lazy results are OFF on Metal, so measurement 1's chain may bring every intermediate
  home whatever the boundary does -- in which case the finding is about the tier, not
  about the handle, and the handle's claim is simply not load-bearing there;
- the resident set on Metal is the GEMV matrix only, so measurement 3's lazy-result half
  may have nothing resident to write into.

## If it does not hold

The fallback is materializing in `floatArrayResult`, which is CORRECT today and only costs
the round trip -- so a failure is a performance finding, not a correctness one. Say which
in `.kb/jvm-export.md` either way.

## Acceptance

The three measurements on Metal, added beside the CUDA ones in `.kb/gpu.md` (the "Lazy
results and the resident tier on Metal" section) and in `.kb/jvm-export.md`, whose
`--gpu` residency paragraph currently ends "Metal is not measured".
