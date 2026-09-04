# `--parallel`: the `--simd` matrix products across cores

A MODIFIER of `--simd` — it intercepts nothing of its own — running the matrix products
over a range of output rows per thread. Read `.kb/vec.md` and `.kb/linalg-simd.md` first.

## Invariant: row-independent kernels only, and bit-identity

`JvmSimdVectorTemplate.matvecRows` / `laMatmulRows` (interpreter twins
`VecSimdKernels.matvecRows` / `LinalgSimdKernels.matmulRows`) compute a row range with
exactly the chain the serial kernel runs — same `SPECIES_128` f32 accumulator, same
two-rounding mul-then-add, same scalar tail in index order — so **which thread runs which
row cannot change a bit**, and every byte-identity statement in `.kb/linalg-simd.md`
holds under `--simd --parallel`.

- **Trap: the row's lane reduction must not use
  `DoubleVector`/`FloatVector#reduceLanes(ADD)`.** The JDK does not pin the fold order of
  a floating-point `ADD` reduction to lane order, and on a warm JVM it can change mid-run
  when a hotter compilation tier replaces a colder one: two calls of the identical
  `matvecRows` on the identical row can legally answer a different bit once recompiled.
  Both files sum a `vacc`'s lanes with a private `sumLanes`/`sumLanesF` helper — a plain
  ascending-index scalar `+=` over `v.lane(i)` — because the JLS pins scalar
  floating-point addition order. `sum`/`dot`/`matvecRows`/`matvecRowsF` all go through
  it; a new f64/f32 reduction must too.
- Split members: `vec:matvec`, `vec:matvec-into`, `linalg:dot` in its matrix-by-vector
  and matrix-by-matrix cases (row-vector-by-matrix is one output row, stays serial), and
  `linalg::%la-matmul-nd` (rows counted across the whole stack, `batches * n`; a batch
  boundary is just another row boundary).
- **Never a reduction** (`sum` / two-vector `dot` / `norm` / `mean`, the axis folds):
  there the fold order IS the value. Never the element-wise kernels: bandwidth-bound, a
  dispatch costs more.
- The serial path is untouched; the parallel entries are four more methods on the same
  bridge (`simdMatvecParallel`, `simdMatvecIntoParallel`, `laDotParallel`,
  `laMatmulNdParallel`) and the emitted class differs by those names alone.

## The knob

- `--parallel` is value-less (`CliOptions.noValueKeys`), requires `--simd` and is a hard
  error without it on the interpreter, REPL and compiler
  (`RontoLispCli.requireSimdForParallel`, `JvmLispCompiler`'s 7-argument constructor).
  Hard error on `.wasm` output: neither WASM backend has threads, and a silent no-op is
  what an acceleration flag exists to prevent.
- `RONTOLISP_THREADS` = thread count, CALLING thread included (default
  `availableProcessors`; `1` = serial; malformed warns once on stderr). Read once at the
  first call worth splitting by `JvmSimdVectorTemplate.parallelThreads` /
  `eval/SimdParallel.threads`.
- Pool: `threads - 1` daemon workers (`rontolisp-parallel-N`), started lazily at that
  call. NOT a `ForkJoinPool`. JVM dispatch is in `JvmSimdVectorTemplate`, one class with
  lambdas, `LockSupport`, `AtomicInteger`s and an `Object[]` job record because the
  single-blob injection can carry no nested class (`.kb/template-class-embedding.md`);
  the interpreter twin `eval/SimdParallel` is the same operation for operation with a
  real `RowKernel` interface and a `Job` record. One call at a time (synchronized).
- `--gpu`: the parallel lanes sit strictly BELOW the device decision.
  `compileGpuMatvec` and `JvmLinalgKernelCompiler` emit device -> lane-or-defun chains
  whose lane rung is the parallel entry; the device attempt, and so `CudaResidency` (not
  thread-safe), runs on the calling thread only. `LinalgGpu.installVec` wraps whatever
  `VecSimd` bound.

## The shape, decided by measurement

- **Who splits decides everything.** `pool.invoke(task)` from an outside thread hands ONE
  task to ONE worker which splits it alone while the caller parks (0.15-0.9x). The caller
  must halve the row range, hand the right halves out, compute the left-most leaf itself,
  and SPIN for the rest rather than `join()`.
- **Back-to-back calls are not the workload.** A decode loop runs ~50-200 us of boxed
  Lisp between GEMVs and a `ForkJoinPool` worker parks within microseconds of going idle,
  so in situ every dispatch paid the unpark chain (0.5-0.9x). The fix is what every BLAS
  does: workers SPIN (`Thread.onSpinWait` on an epoch) and park only after `SPIN_NANOS` =
  1 ms idle, with rows claimed in grain-sized leaves off one `AtomicInteger` by caller and
  workers alike (so a slow core takes fewer leaves). Cost: while a loop of products runs
  the workers are busy cores; a millisecond after the last call they sleep.
- **Dispatch floor ~3 us**, so the threshold is **2^15 multiply-adds** (`PARALLEL_MIN_WORK`
  / `SimdParallel.MIN_WORK`) and at least two rows, with `threads - 1` workers plus the
  caller, a **grain of 2^13 multiply-adds per leaf, at most 4 leaves per thread** (a
  fixed 2^13 grain alone puts 1100 leaves on the claim counter; a fixed 2^14 loses at
  768x288). One threshold for both widths: f64 is ~2x the per-MAC cost, so the bound is
  conservative there.
- **The job is one object read once per call by each worker** (`Object[]` / `Job`): the
  kernel, the shape and the call's OWN claim and pending counters. With shared counters a
  worker descheduled between its last leaf and its final empty claim would claim and
  decrement the NEXT call's rows under the OLD kernel, and the caller would return with
  rows missing.
- **Parking is a Dekker handshake**: a worker publishes `parked`, rechecks the epoch,
  then parks; the caller bumps the epoch, then scans the flags and unparks. A stale permit
  costs one extra look.
- **The yield matters as much as the spin.** 19 pure spinners on a 20-core box crowd out
  the caller, the JIT and the GC (267 tok/s at 20 threads vs 330 at 10);
  `Thread.yield()` every 64 spins makes the default count within noise of the best.

Expected: 1.7-5.6x per GEMV at llama2 shapes, ceiling is memory bandwidth not scheduling;
~1.8x whole-program on a decode loop.

**`--gpu --simd --parallel` is slower than either flag alone on llama2**: the device takes
the big GEMVs, the spinning workers compete with the driver's threads, and the lanes get
only the small projections. Correct and pinned, documented as not a win here. The
interpreter gains nothing from the flag on llama2 either (the tree walk dominates).

## `linalg:` GEMM against `--blas`

The row split buys the GEMM 5.6-7.6x over the serial lanes, and **a tuned threaded BLAS
still wins by 1.3-3.8x** (a blocked SGEMM against an `ikj` lane loop). So `--blas` stays
the answer for a GEMM-bound program with a library; `--parallel` where there is none, for
the GEMV (`vec:matvec`, outside `--blas`'s set), and for the stacked product. `--blas`
results differ from `--simd`'s in the last digits (the library's fold order); the parallel
ones do not. The interpreter's `--simd --parallel` lands on the JVM's figures.

## Tests

| what | where |
|---|---|
| JVM: bit-identity at both widths above the threshold (GEMV, `-into`, `dot` M.v and M.M, the stacked product), below it, the emit gate (four names and nothing else), `--parallel` without `--simd` refused, the `--gpu` chain (`underGpuTheParallelLanesSitBelowTheDeviceDecision`) | `codegen/jvm/JvmSimdParallelCompilerTest` |
| interpreter: dispatch covers every row once and joins, a failing leaf surfaces on the caller, the threshold, bit-identity, the flag inert without `--simd` | `eval/SimdParallelTest` |
| value-less flag; refused without `--simd`, refused on `.wasm`, binds the parallel entries on `.class` | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |
| llama2 story byte-identical under `--simd --parallel` on interpreter and JVM (`parallel: true` in `examples/examples.yaml`) | `e2e/ExamplesE2eTest` |
