# What a threaded CBLAS costs a short call (2026-09-02)

A throwaway probe kept for reproducibility, NOT project code: it is outside `src/`, is not
in the reactor, is not formatted by `spring-javaformat:apply`, and nothing builds or tests
it. It is the companion of `../471-vec-matvec-is-outside-the-blas-intercepted-set/`'s
`GemvProbe.java`, and it exists because that probe's threaded column cannot be trusted on
its own.

The item it was written for is closed and deleted; this directory outlived it deliberately,
the way `../123-gpu-acceleration/` did, because `../../.kb/linalg-blas.md` cites it.
`../history/` reads the deleted item back: `git show <commit>~:<path>`.

## What it measures, and why not back to back

`GemvProbe` times calls one after another with nothing in between. OpenBLAS's pthread pool
keeps spinning through that, so it never pays the wake-up a real program makes it pay: the
same 288x288 `#f` GEMV measured **17.4 us** back to back and **90.0 us** with ~200 us of
unrelated Java work between calls, against 13.3 us capped to one thread. A ratio table
built the first way says threading is a 2x win at that shape; the program says it is a 6.8x
loss. So this probe puts the unrelated work in, and reports what one call costs the caller.

It also prints what the library will say about its own thread pool -- the optional symbols
`--blas` asks (`openblas_get_num_threads`, `mkl_get_max_threads`), and the setters that
`--blas-threads=N`, considered and declined, would have needed.

## Running it

```sh
java --enable-native-access=ALL-UNNAMED ThreadBarrierProbe.java
OPENBLAS_NUM_THREADS=1 java --enable-native-access=ALL-UNNAMED ThreadBarrierProbe.java
```

A single-class JDK source-launcher program: no build step, no dependency, Java 22+ for the
FFM API. `PROBE_BLAS` (or `RONTOLISP_BLAS`) names a library other than the platform default.

**Run it on a quiet machine and check the load average first.** The whole measurement is a
thread pool waking up, so anything else on the cores moves it by an order of magnitude:
the same 288x288 row read 90 us at load 11 and 692 us at load 60 on the same host, the same
afternoon. The `.kb` table is the quiet run.

## What it showed

Crossover between 0.4 and 4 Mflop per call, the SAME for GEMV and GEMM: what loses to a
threaded library is a short call, not a particular kernel. Below it threading costs up to
6.8x, above it it buys up to 6.2x. That is why the note `--blas` now prints is triggered by
the calls a program makes and not by the flag -- a warning at flag time would have fired
identically on `examples/ml/blas-matmul.lisp`, which wants every one of those threads.
Numbers and the full decision: `../../.kb/linalg-blas.md`, "The two contracts".
