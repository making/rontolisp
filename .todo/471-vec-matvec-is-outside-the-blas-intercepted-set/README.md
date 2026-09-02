# Is `cblas_?gemv` worth binding for `vec:matvec`? (2026-08, re-measured 2026-09-02)

A throwaway probe kept for reproducibility, NOT project code: it is outside `src/`, is not
in the reactor, is not formatted by `spring-javaformat:apply`, and nothing builds or tests
it. It exists so that the GEMV numbers in `../../.kb/linalg-blas.md` can be re-derived on
other hardware -- and they need to be, because the two machines measured so far disagree
by a factor of three.

The item it was written for, `../471-vec-matvec-is-outside-the-blas-intercepted-set.md`,
is closed and deleted; this directory outlived it deliberately, the way
`../123-gpu-acceleration/` did, because `.kb/linalg-blas.md` cites the file by path.
`../history/` reads the deleted item back: `git show <commit>~:<path>`.

## What it measures, and why not against a scalar loop

The comparison that matters is `cblas_?gemv` against the **lane kernel the emitted bridge
actually runs** (`JvmSimdVectorTemplate.simdMatvec`), not against a scalar loop: `#f`
accumulates in `FloatVector.SPECIES_128` -- four lanes, single precision, the pinned
reduction species -- `#d` in `DoubleVector.SPECIES_PREFERRED`, and both fold with
`mul().add()` rather than `fma()`. A ratio over a scalar loop would flatter the library by
whatever `--simd` was already worth.

The shapes are the ones the shipped examples spend their time in: 256x256 is
`examples/ml/simd-gemv.lisp`, and 288x288 / 288x768 / 768x288 / 4096x288 are llama2
stories15M's attention, FFN and classifier-head projections. The last column is the max
relative difference against the lane kernel -- the number that decides whether a pinned
`argmax` or token id can move.

## Running it

```sh
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED GemvProbe.java
```

A single-class JDK source-launcher program: no build step, no dependency, Java 22+ for the
FFM API. It times Accelerate on macOS and `libopenblas.so.0` elsewhere; `PROBE_BLAS` (or
`RONTOLISP_BLAS`) names another library outright.

**Set the library's thread count before drawing any conclusion.** A gemv is memory-bound
and short, so a threaded BLAS pays a barrier per call that the call cannot amortize:

```sh
OPENBLAS_NUM_THREADS=1 java --add-modules jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED GemvProbe.java
```

On a 64-core Xeon the two settings differ by more than an order of magnitude in both
directions -- and end to end, at these shapes, the threaded default is a large net LOSS.
`.kb/linalg-blas.md` has both columns and the llama2 tok/s that follow from them.
