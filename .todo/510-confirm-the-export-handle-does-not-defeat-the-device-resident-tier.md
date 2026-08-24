# Confirm the jvm-export handle does not defeat the device-resident tier

Difficulty: Medium

Carved out of `.todo/504` when the packed float-array boundary type landed. Everything
about `:float-vector` / `:float-matrix` is built, tested and documented
(`.kb/jvm-export.md`, "The packed float array"); this is the ONE claim in it that a
device-less machine cannot check. `.todo/501` already named it as the single paragraph of
that item needing hardware.

## The claim

A handle a `--gpu` kernel returns must NOT force a materialization the next call would
only re-upload. What ships is built for that:

- `RontoBoundary.floatArrayResult` wraps the answered array **without** calling
  `_gpuMaterialize`, so a Java-side chain `h = Kernels.step(h)` should keep the result on
  the device across every crossing;
- `RontoFloatArray` instead adopts the generated class and resolves its private
  `_gpuMaterialize` / `_gpuWritten` guards, so the download happens on the first
  `get`/`set`/`toArray` and not before;
- a lazy result's host array is the HEADER ALONE (`.todo/492`), so `checkPacked` requires
  `1 + rank` elements and the handle reads rank/dims/size off the stub without a guard.

The seam itself is pinned with a stand-in owner class
(`RontoFloatArrayTest#aHostReadGoesThroughTheOwnerClassResidencyGuard`), and the
reflective resolution was verified against a real `--gpu` class on a device-less machine
(both guards resolve to `MethodHandle(Object)Object`). What is NOT verified is the
behavior that needs a device.

## What to measure, and where

**Two halves.** The lazy/resident tier was settled separately for CUDA (`.todo/492`/`493`)
and for Metal (`.todo/494`), so both have to answer, on the GB10 and on a Mac:

1. A Java loop over a `--gpu` export whose kernel is device-eligible (`vec:matvec` over a
   resident matrix is the measured one, `.kb/gpu.md` "The GEMV, and the matrix that
   stays") stays resident across the boundary: no download-and-re-upload per iteration.
   `GpuThresholds.dirtyCount()` is the interpreter's assertion for "really stayed"; the
   JVM class output cannot expose it, so measure the transfer volume or the per-iteration
   time against the same loop in Lisp.
2. `toArray()` on a handle a device kernel returned brings the result home exactly once
   and answers the right numbers -- the same oracle as
   `everyEnumeratedReaderMaterializesTheDeviceResult`: the program without `--gpu`.
3. `set(i, v)` through a handle on a resident array lands on the array the guard ANSWERS,
   so a following kernel call sees the write.

`examples/jvm/bench/` is the harness to extend; it already compiles a library and calls it
from Java, so the `--gpu` row is a flag and a second kernel.

## If it does not hold

The fallback is materializing in `floatArrayResult`, which is CORRECT today and only
costs the round trip -- so a failure here is a performance finding, not a correctness one.
Say which in `.kb/jvm-export.md` either way: the file currently says the confirmation is
outstanding.

## Acceptance

The three measurements above on CUDA and on Metal, the numbers in `.kb/gpu.md` beside the
resident-tier table, and `.kb/jvm-export.md`'s "Not yet confirmed on a device" paragraph
replaced by what was measured.
