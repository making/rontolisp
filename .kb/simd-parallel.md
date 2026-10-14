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
  the left-most leaf itself and SPINS for the rest -- `pool.invoke` from outside runs at a
  fraction of the speed at best.
- Workers SPIN (`Thread.onSpinWait` on an epoch) and park only after `SPIN_NANOS` = 1 ms idle;
  rows are claimed in grain-sized leaves off one `AtomicInteger` by caller and workers alike. A
  parking `ForkJoinPool` paid the unpark chain every dispatch because a decode loop
  runs tens of microseconds of boxed Lisp between GEMVs.
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
- Expected a multiple of the serial rate per GEMV. **"Ceiling is memory
  bandwidth" holds only once the matrix is unambiguously larger than cache** -- a
  `.todo/702` size sweep on GB10 (2026-09-06) found the f32 parallel arm's rate is a hump,
  not a flat ceiling: BELOW even the serial rate at small shapes (too few leaves
  for the thread count, a machinery effect), well above the rate `.todo/488` had called "this box's
  ceiling" while cache-resident, and back down only once the matrix is certainly out of cache.
  The single ceiling `.todo/488` had read at both a cache-resident and an out-of-cache shape
  was two points on that
  hump landing near each other, not one bandwidth ceiling binding both -- see
  `.todo/artefacts/702-the-parallel-cap-is-the-machinery-or-memory-one-run-decides/README.md` for
  the full sweep and the leaf/grain arithmetic behind the small-shape undershoot. **The hump
  also has a TROUGH in the middle of it, and it is thread-count-dependent, not
  shape-alone**: `.todo/702` measured its dip just past the cache-resident band under
  `RONTOLISP_THREADS=20` (this box's default at the time); `.todo/713`'s finer sweep
  (2026-09-06, same box) found a broad trough there -- not a spike at one shape -- that
  tracks the matrix's TOTAL BYTE SIZE (confirmed non-square, both dimensions
  independently) rather than either dimension or a leaf-count quantization, deepens from
  mid thread counts up and is essentially absent at low counts, and is **invisible at this
  box's CURRENT default** (`.todo/697` halved the default the same day, after
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
- GEMM: the row split buys a large multiple, but a tuned threaded BLAS still beats it, so `--blas`
  stays the answer where a library exists. `--blas` differs in the last digits; parallel does not.

## The default is HALF the processors, and the caller may not help a straggler
Both settled by measurement on 2026-09-06 (`.todo/697`, dorian: 2 sockets x 16 cores x 2
threads = 64, GraalVM 25.0.4, JVM class output of `examples/llm`, Qwen3-0.6B BF16 read as
f32, `-t 0 -n 64`, no other rontolisp lane on the box).

- **A pool as wide as the machine is slower than half of it even when nothing else runs**:
  throughput is flat from half width to just under full and BENDS DOWN at full width. Under
  `ParallelContentionBench`
  (a GEMV in a decode-shaped loop, a wide idle gap between calls) half width beat
  full width in every cell, idle or with busy cores beside it. GB10's README row says the
  same from the other direction (a reduced `RONTOLISP_THREADS`
  beat the full default on a smaller-core box). So the default is half, and `RONTOLISP_THREADS`
  still overrides it.
- **What the full-machine default cost was a TAIL, not a mean.** The reported collapse at
  full width reproduced ONCE: two copies of the decode
  loop started five seconds apart. It did
  NOT reproduce with pure-CPU spinner threads beside a single run, nor with a maven build
  beside it, nor on three later repeats of the pair. Do not expect a bad number on
  demand: the mechanism is a worker descheduled while it holds a leaf, which needs the box
  genuinely oversubscribed, and one run in four saw it.
- **Rejected, measured: the caller helping the straggler.** Two shapes were built and
  benched against the shipped one, both LOSING 20-45% on the healthy path:
  (1) per-leaf done flags (`AtomicIntegerArray`) so the caller can RE-RUN a leaf a
  descheduled worker still holds -- legal, since a leaf is a deterministic write of rows no
  leaf reads, so running it twice writes the same bits -- with the rescue budget set to the
  call's own serial cost. It is slower at full width idle regardless of the budget, so the
  cost is the per-leaf flag traffic (every core writing its flag and the caller reading all
  of them), not the rescue. Keeping a shared
  countdown for the fast path does not help: exactly-once accounting needs the same per-leaf
  write either way, and returning before a straggler has stopped writing is not an option --
  it would let a stale leaf land in the NEXT call's `matvec-into` destination.
  (2) `Thread.yield()` every 64 spins in the caller's wait (the workers' own rule, on the
  theory that the caller occupies the CPU the straggler needs). No consistent gain, and the
  worst cell was several times the best -- once the caller yields on a full box it waits to be
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
