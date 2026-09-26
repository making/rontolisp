# `--parallel`: the `--simd` matrix products across cores

A MODIFIER of `--simd` -- it intercepts nothing of its own -- running the matrix products over a
range of output rows per thread. Read `.kb/vec.md` and `.kb/linalg-simd.md` first.

## Invariant: row-independent kernels only, and bit-identity
`JvmSimdVectorTemplate.matvecRows` / `laMatmulRows` (interpreter twins `VecSimdKernels.matvecRows`
/ `LinalgSimdKernels.matmulRows`) run exactly the serial kernel's chain, so **which thread runs
which row cannot change a bit** and every byte-identity statement in `.kb/linalg-simd.md` holds.

- **Trap: a row's lane reduction must not use `DoubleVector`/`FloatVector#reduceLanes(ADD)`.** The
  JDK does not pin its fold order to lane order and a hotter tier can change it mid-run. Both
  files use a private `sumLanes`/`sumLanesF` -- ascending-index scalar `+=` over `v.lane(i)`,
  order pinned by the JLS. `sum`/`dot`/`matvecRows`/`matvecRowsF` go through it; a new f64/f32
  reduction must too.
- Split members: `vec:matvec`, `vec:matvec-into`, `linalg:dot` (matrix-by-vector and
  matrix-by-matrix; row-vector-by-matrix stays serial), `linalg::%la-matmul-nd` (rows counted
  `batches * n`).
- **Never a reduction** (`sum`, two-vector `dot`, `norm`, `mean`, axis folds): there the fold
  order IS the value. Never the element-wise kernels: bandwidth-bound.
- The serial path is untouched; the emitted class differs only by `simdMatvecParallel`,
  `simdMatvecIntoParallel`, `laDotParallel`, `laMatmulNdParallel`.

## The knob
- `--parallel` is value-less (`CliOptions.noValueKeys`), requires `--simd`, hard error without it
  on interpreter/REPL/compiler (`RontoLispCli.requireSimdForParallel`, `JvmLispCompiler.Builder#build`) and hard error on `.wasm` output (no threads there).
- `RONTOLISP_THREADS` = thread count, CALLING thread included (default
  `min(cpus, max(2, cpus / 2))` -- HALF the box, see below; `1` = serial; malformed warns
  once). Read once by `JvmSimdVectorTemplate.parallelThreads` / `eval/SimdParallel.threads`,
  both defaulting through a `parallelDefaultThreads` / `defaultThreads` twin pinned by
  `JvmSimdParallelCompilerTest#theEmittedPoolDefaultsToHalfTheBoxToo` and
  `SimdParallelTest#theDefaultThreadCountIsHalfTheBoxAndNeverFillsIt`.
- Pool: `threads - 1` daemon workers (`rontolisp-parallel-N`), lazy, NOT a `ForkJoinPool`, one
  call at a time. JVM dispatch is one flat class with an `Object[]` job record (a single-file
  template ships no nested class, `.kb/template-class-embedding.md`); `eval/SimdParallel` is
  the twin with a real `RowKernel`.
- `--gpu`: parallel lanes sit strictly BELOW the device decision (`compileGpuMatvec`,
  `JvmLinalgKernelCompiler`), so `CudaResidency` (not thread-safe) stays on the calling thread.
  `LinalgGpu.installVec` wraps whatever `VecSimd` bound.

## Shape, decided by measurement
- **Who splits decides everything**: the caller halves the range, hands out right halves, computes
  the left-most leaf itself and SPINS for the rest -- `pool.invoke` from outside is 0.15-0.9x.
- Workers SPIN (`Thread.onSpinWait` on an epoch) and park only after `SPIN_NANOS` = 1 ms idle;
  rows are claimed in grain-sized leaves off one `AtomicInteger` by caller and workers alike. A
  parking `ForkJoinPool` paid the unpark chain every dispatch (0.5-0.9x) because a decode loop
  runs ~50-200 us of boxed Lisp between GEMVs.
- Dispatch floor ~3 us -> threshold **2^15 multiply-adds** (`PARALLEL_MIN_WORK` /
  `SimdParallel.MIN_WORK`) and at least two rows; **grain 2^13 MACs per leaf, at most 4 leaves per
  thread**. One threshold for both widths.
- **The job is one object read once per call by each worker** with the call's OWN claim and
  pending counters; shared counters let a descheduled worker claim the NEXT call's rows.
- **Parking is a Dekker handshake**: worker publishes `parked`, rechecks the epoch, parks; caller
  bumps the epoch then scans and unparks.
- **The yield matters as much as the spin**: `Thread.yield()` every 64 spins (19 pure spinners on
  a 20-core box crowd out the caller, the JIT and the GC). It belongs to the WORKERS' idle spin
  only -- the same rule in the caller's wait for the last leaf measured worse (below).
- Expected 1.7-5.6x per GEMV, ~1.8x whole-program on a decode loop. **"Ceiling is memory
  bandwidth" holds only once the matrix is unambiguously larger than cache** -- a
  `.todo/702` size sweep on GB10 (2026-09-06) found the f32 parallel arm's rate is a hump,
  not a flat ceiling: 13 Gelem/s at 256x256 (below even the serial rate -- too few leaves
  for the thread count, a machinery effect), 48-69 Gelem/s at 1024-2048x1024-2048
  (cache-resident, well above the ~42 Gelem/s `.todo/488` had called "this box's
  ceiling"), and back down to 41-44 only at 4096x4096 (67 MB, certainly out of cache).
  The 41-42 `.todo/488` measured at both 1024x1024 and 4096x4096 was two points on that
  hump landing near each other, not one bandwidth ceiling binding both -- see
  `.todo/artefacts/702-the-parallel-cap-is-the-machinery-or-memory-one-run-decides/README.md` for
  the full sweep and the leaf/grain arithmetic behind the 256x256 undershoot. **The hump
  also has a TROUGH in the middle of it, and it is thread-count-dependent, not
  shape-alone**: `.todo/702` measured its 25-28 Gelem/s dip at 3072x3072 under
  `RONTOLISP_THREADS=20` (this box's default at the time); `.todo/713`'s finer sweep
  (2026-09-06, same box) found a broad 2560-3456 trough -- not a spike at 3072 -- that
  tracks the matrix's TOTAL BYTE SIZE (~26-50 MB, confirmed non-square, both dimensions
  independently) rather than either dimension or a leaf-count quantization, deepens from
  threads=12 up through 20 and is essentially absent at 8-10, and is **invisible at this
  box's CURRENT default** (`.todo/697` halved the default to 10 the same day, after
  `.todo/702`'s own measurement) -- see
  `.todo/artefacts/713-the-3072x3072-parallel-gemv-dip-todo-702-left-open/README.md`.
  Two consequences the checkpoint lanes carried out of this: **a parallel GEMV rate is a
  property of how the work was cut up, not of the machine and not of the weights** (Qwen3.5's
  Gated DeltaNet does 576 small 128x128 GEMVs a token against LFM2.5's ~30 big matvecs, so the
  one paying more dispatch saturates earlier; the signature is a per-model saturation point,
  tok/s over tok/s within one model, and `examples/llm/README.md`'s knee did not move when
  `.todo/489` halved the bytes); and **a cross-model GB/s comparison is NOT evidence about the
  cap**, because it divides by an activation-blind parameter-count estimate that omits exactly
  the recurrent state Qwen3.5 streams.
- **`--gpu --simd --parallel` is slower than either alone on llm** -- correct and pinned,
  documented as not a win. The interpreter gains nothing on llm either.
- GEMM: the row split buys 5.6-7.6x, but a tuned threaded BLAS still wins 1.3-3.8x, so `--blas`
  stays the answer where a library exists. `--blas` differs in the last digits; parallel does not.

## The default is HALF the processors, and the caller may not help a straggler
Both settled by measurement on 2026-09-06 (`.todo/697`, dorian: 2 sockets x 16 cores x 2
threads = 64, GraalVM 25.0.4, JVM class output of `examples/llm`, Qwen3-0.6B BF16 read as
f32, `-t 0 -n 64`, no other rontolisp lane on the box).

- **A pool as wide as the machine is slower than half of it even when nothing else runs**:
  8.70 tok/s at 64 threads, 9.45 at 32, 9.55 at 16, 7.58 at 8, 5.50 at 4, 2.27 at 1 -- the
  curve is flat from 16 to 32 and BENDS DOWN at 64. Under `ParallelContentionBench`
  (1024x1024 f32 GEMV in a decode-shaped loop, a 100 us gap between calls) 16 threads beat
  64 in every cell, idle or with 16 or 48 busy cores beside it: 0.027-0.032 ms/call against
  0.041-0.087. GB10's README row says the same from the other direction (`RONTOLISP_THREADS=10`
  beat the 20 default on a 20-core box). So the default is half, and `RONTOLISP_THREADS`
  still overrides it.
- **What the full-machine default cost was a TAIL, not a mean.** The reported collapse
  (0.62 tok/s at 64 threads against 9.88 at 32) reproduced ONCE: two copies of the decode
  loop started five seconds apart, 64 threads each, printed 0.57 and 3.38 tok/s. It did
  NOT reproduce with 6, 16 or 64 pure-CPU spinner threads beside a single run (8.68 / 8.23
  / 7.00), nor with a maven build beside it (8.76), nor on three later repeats of the pair
  (8.60/8.44, 8.04/8.51 -- and 8.75/9.43 at the new default). Do not expect a bad number on
  demand: the mechanism is a worker descheduled while it holds a leaf, which needs the box
  genuinely oversubscribed, and one run in four saw it.
- **Rejected, measured: the caller helping the straggler.** Two shapes were built and
  benched against the shipped one, both LOSING 20-45% on the healthy path:
  (1) per-leaf done flags (`AtomicIntegerArray`) so the caller can RE-RUN a leaf a
  descheduled worker still holds -- legal, since a leaf is a deterministic write of rows no
  leaf reads, so running it twice writes the same bits -- with the rescue budget set to the
  call's own serial cost. 0.041 -> 0.061 ms/call at 64 threads idle whether the budget was
  20 us or 262 us, so the cost is the per-leaf flag traffic (64 cores writing 128 flags over
  8 cache lines, and the caller reading all of them), not the rescue. Keeping a shared
  countdown for the fast path does not help: exactly-once accounting needs the same per-leaf
  write either way, and returning before a straggler has stopped writing is not an option --
  it would let a stale leaf land in the NEXT call's `matvec-into` destination.
  (2) `Thread.yield()` every 64 spins in the caller's wait (the workers' own rule, on the
  theory that the caller occupies the CPU the straggler needs). No consistent gain and one
  0.220 ms/call cell against 0.066 -- once the caller yields on a full box it waits to be
  rescheduled among everything else. **The bench is `eval/ParallelContentionBench`; re-run it
  before trying a third shape.**

## Tests
- `codegen/jvm/JvmSimdParallelCompilerTest` -- bit-identity at both widths above and below the
  threshold, the emit gate (four names and nothing else), `--parallel` without `--simd` refused,
  `underGpuTheParallelLanesSitBelowTheDeviceDecision`.
- `eval/SimdParallelTest` -- every row once, a failing leaf surfaces on the caller, threshold,
  bit-identity, flag inert without `--simd`.
- `cli/CliOptionsTest`, `cli/RontoLispCliTest`; `e2e/ExamplesE2eTest` (llm byte-identical,
  `parallel: true` in `examples/examples.yaml`).
