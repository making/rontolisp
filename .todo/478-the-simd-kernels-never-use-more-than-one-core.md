# 478. The `--simd` kernels never use more than one core

Difficulty: High

Surfaced 2026-08-22 while checking `examples/llama2/README.md`'s comparison against
[kishida's Java Vector API port of run.c](https://gist.github.com/kishida/05656bfcbe840f269784f7dbbee5928e).
The table claimed rontolisp `--simd` (220) beat that port (187). It did not: the 187 was
measured on 2026-08-19 on the 64-core x86 box and the 220 on 2026-08-22 on the GB10, and
**the port's `matmul` is `IntStream.range(0, d).parallel().forEach(...)`** -- a
multi-core program that had been quoted next to our single-core one. Re-measured together
on the GB10 (stories15M, the 222-token `-t 0` "Once upon a time" story, medians of three
interleaved runs, nothing pinned; the README now carries this table):

| | threads | tok/s | ms/token |
|---|---|---|---|
| rontolisp JVM `--simd` | 1 | 218 | 4.6 |
| rontolisp JVM `--gpu --simd` | 1 + device | 283 | 3.5 |
| `run.c -O2` | 1 | 147 | 6.8 |
| the gist, `.parallel()` removed | 1 | 297 | 3.4 |
| **the gist as published** | 20 | **535** | **1.9** |

So there are two separate gaps, and only the first one is `.todo/457`:

1. **Single thread against single thread we lose by ~1.2 ms a token** (4.6 vs 3.4). That
   is the boxed attention / RoPE / KV-cache glue `.todo/457` is about, not the GEMV.
2. **There is no multi-core lane at all.** Every `--simd` kernel on every backend runs on
   the calling thread. `--blas` is the one flag that reaches other cores today, and only
   because a tuned BLAS threads internally (`.kb/linalg-blas.md`, "Threads") -- and it
   does not reach this program at all, because `vec:matvec` is outside its intercepted
   set (`.todo/471`). This item is gap 2.

## The case for row-parallel GEMV, and why it is free of the precision contract

A GEMV is `d` independent row reductions. `JvmSimdVectorTemplate.matvecF` (and
`.matvec`, and `VecSimdKernels.matvec*`) accumulates each row in ONE
`FloatVector.SPECIES_128` chain in index order and writes `r[row]`; **which thread runs
which row cannot change a single bit.** Splitting rows across threads is therefore the
one parallelisation that does not touch `.kb/linalg-simd.md`'s pinned-lane contract --
unlike a parallel `vec:sum` / `vec:dot` / `linalg:norm`, where the fold order IS the
value. Any work here must stay on that line: **parallelise row-independent kernels only,
never a reduction.**

`.todo/478-the-simd-kernels-never-use-more-than-one-core/GemvParallelProbe.java` measures
it at llama2's four shapes, serial against a chunked `ForkJoinPool` and against the
gist's own shape (one parallel stream over rows on the common pool), and asserts the
results are bit-identical (they are, at every shape and every thread count):

| shape | serial us | chunked FJ | parallel stream | speedup |
|---|---|---|---|---|
| `wq/wk/wv/wo` 288x288 | 10 | 36 | 17-22 | **0.5x** |
| `w1/w3` 768x288 | 27-51 | 48-68 | 17-20 | 1.4-2.9x |
| `w2` 288x768 | 32-53 | 55-112 | 20 | 1.6-2.6x |
| classifier 32000x288 | 1210 | 930-1070 | 380-415 | **2.9-3.2x** |

Three things the probe settles before any code is written:

- **The fixed cost of one parallel dispatch is ~17-20 us**, so the crossover is around
  ~25-30 us of serial work, i.e. ~0.2M multiply-adds. llama2's 24 projections per token
  (288x288, ~10 us) must NOT be parallelised; its 18 feed-forward matrices and its head
  must.
- **A hand-rolled `ForkJoinPool` with `submit`/`get` per call is not competitive**
  (0.1-1.3x): the win comes from a work-stealing pool the CALLER participates in. Whatever
  we build has to have that shape, not a "hand the chunks to a pool and wait" shape.
- **The ceiling is not linear.** 3x on a 37 MB matrix on a box with 20 cores is memory,
  not scheduling; the gist's whole-program 1.8x (297 -> 535) is the number to expect.

Projection for llama2 from the probe, and it is deliberately unflattering: of the 2.4 ms
of GEMV in a 4.6 ms token, ~1.85 ms is in the 19 shapes above the crossover; at 2.9x plus
19 dispatches that is ~0.98 ms, so the token goes 4.6 -> ~3.7 ms = **~270 tok/s from this
item alone**. Matching the published gist needs `.todo/457`'s glue work as well
(2.2 ms of boxed Lisp -> ~1 ms would put the token near 2.0 ms = ~500 tok/s).

## Design questions to settle first (do not start coding without an answer)

1. **The knob.** Threads must never appear behind a user's back: a `vec:` program that
   silently occupies 20 cores changes CPU accounting, container limits and the meaning of
   every benchmark in this repo, and two of the four backends cannot do it at all. The
   proposal is a value-less `--parallel` flag alongside `--simd` (the `--simd` dead-flag
   lesson in `cli/CliOptionsTest` applies), with the thread count from
   `RONTOLISP_THREADS` (default `availableProcessors`), mirroring how `.kb/linalg-blas.md`
   defers to `OPENBLAS_NUM_THREADS` rather than fighting it. The alternative -- fold it
   into `--simd` above a work threshold -- is simpler for the user and worse for
   everything else; it needs an explicit decision from the user before Phase 1.
2. **Where the pool lives.** The JVM backend EMBEDS its `--simd` bridge into the compiled
   `.class` (`JvmSimdRuntimeBuilder` over `JvmSimdVectorTemplate`), so a pool means a
   static field and worker threads inside every compiled program: daemon threads, created
   lazily on the first above-threshold call, and never on a program that has none. Check
   what that does to a `--gpu` build's shutdown path and to native-image startup.
3. **`--gpu` interaction.** `JvmSimdCompiler.compileGpuMatvec` is a device-then-lane chain
   and `am.ik.gpu.CudaResidency` is NOT thread-safe. Parallel lanes must sit strictly
   BELOW the device decision (the lane fallback), never around it, or residency must be
   locked -- decide which, and pin it with a test.
4. **Which members.** `vec:matvec` / `vec:matvec-into` first (that is the decode loop and
   `simd-gemv`), then `linalg`'s `%la-matmul-nd` row split. Reductions are excluded by the
   precision contract; the element-wise kernels are bandwidth-bound and pointless.
5. **The wasm backends stay serial** (no threads in wasm-GC or `--no-gc`), so the flag is
   a compile error there rather than a silent no-op, and every cross-backend byte-identity
   test keeps passing unchanged -- which is the whole point of splitting rows.

## Phases

0. Decide question 1 with the user. Re-run `GemvParallelProbe` on the target box.
1. JVM `--simd` `vec:matvec`/`-into`, row-parallel above the measured threshold, behind
   the knob. Gate: `examples/llama2` >= 260 tok/s, story byte-identical on all four
   backends, `JvmLispCompilerTest` + the `--simd` byte-identity tests unchanged.
2. Interpreter (`VecSimdKernels`) on the same knob, same threshold. Gate: the interpreter
   number moves and `LispEvaluatorTest`'s vec cases are bit-for-bit unchanged.
3. `linalg:` matmul rows, and the `--blas` route in `.todo/471` measured against it (a
   tuned `cblas_sgemv` threads internally and may simply win -- if it does, say so and
   stop).
4. Docs: `doc/{en,ja}/guides/simd-acceleration.md` gets the thread note, mirrored, plus
   the llama2 README table re-measured with a threads column.

## Gate

The `examples/llama2` README table, re-measured on one box on one day, showing:
`--simd` single-threaded a NARROW loss to the gist's single-threaded 297 (`.todo/457`
does that half), and `--parallel` (and/or `--blas`, and/or `--gpu`) ABOVE the published
gist's 535. Every backend still tells the same 222-token story, byte for byte, with and
without the flag -- asserted by a test that runs the same GEMV serially and in parallel
and compares bits, not tolerances.
