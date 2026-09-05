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
  on interpreter/REPL/compiler (`RontoLispCli.requireSimdForParallel`, `JvmLispCompiler`'s
  7-argument constructor) and hard error on `.wasm` output (no threads there).
- `RONTOLISP_THREADS` = thread count, CALLING thread included (default `availableProcessors`;
  `1` = serial; malformed warns once). Read once by `JvmSimdVectorTemplate.parallelThreads` /
  `eval/SimdParallel.threads`.
- Pool: `threads - 1` daemon workers (`rontolisp-parallel-N`), lazy, NOT a `ForkJoinPool`, one
  call at a time. JVM dispatch is one flat class with an `Object[]` job record (single-blob
  injection carries no nested class, `.kb/template-class-embedding.md`); `eval/SimdParallel` is
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
  a 20-core box crowd out the caller, the JIT and the GC).
- Expected 1.7-5.6x per GEMV, ~1.8x whole-program on a decode loop; ceiling is memory bandwidth.
- **`--gpu --simd --parallel` is slower than either alone on llm** -- correct and pinned,
  documented as not a win. The interpreter gains nothing on llm either.
- GEMM: the row split buys 5.6-7.6x, but a tuned threaded BLAS still wins 1.3-3.8x, so `--blas`
  stays the answer where a library exists. `--blas` differs in the last digits; parallel does not.

## Tests
- `codegen/jvm/JvmSimdParallelCompilerTest` -- bit-identity at both widths above and below the
  threshold, the emit gate (four names and nothing else), `--parallel` without `--simd` refused,
  `underGpuTheParallelLanesSitBelowTheDeviceDecision`.
- `eval/SimdParallelTest` -- every row once, a failing leaf surfaces on the caller, threshold,
  bit-identity, flag inert without `--simd`.
- `cli/CliOptionsTest`, `cli/RontoLispCliTest`; `e2e/ExamplesE2eTest` (llm byte-identical,
  `parallel: true` in `examples/examples.yaml`).
