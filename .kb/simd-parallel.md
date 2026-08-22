# `--parallel`: the `--simd` matrix products across cores

`.todo/478` (2026-08-22). Until it, every `--simd` kernel on every backend ran on the
calling thread; the one flag that reached a second core was `--blas`, and only because a
tuned BLAS threads internally (`.kb/linalg-blas.md`, "Threads"). `--parallel` is a
MODIFIER of `--simd` -- it intercepts nothing of its own -- that runs the matrix products
over a range of output rows per thread. Read `.kb/vec.md` and `.kb/linalg-simd.md` first;
this file adds one invariant and the measurements behind one dispatch shape.

## The invariant: row-independent kernels only, and bit-identity

A GEMV is `d` independent row reductions, and the `ikj` GEMM folds `k` into each output
row's cells and reads nothing another row writes. `JvmSimdVectorTemplate.matvecRows` /
`laMatmulRows` (and the interpreter twins `VecSimdKernels.matvecRows` /
`LinalgSimdKernels.matmulRows`) compute a row range with exactly the chain the serial
kernel runs -- the same `SPECIES_128` f32 accumulator, the same two-rounding mul-then-add,
the same scalar tail in index order -- so **which thread runs which row cannot change a
bit**, and every byte-identity statement `.kb/linalg-simd.md` makes about `--simd` holds
under `--simd --parallel` unchanged. That is the whole contract, and its consequence:

- **Only row-independent kernels are split.** The members: `vec:matvec`,
  `vec:matvec-into`, `linalg:dot` in its matrix-by-vector and matrix-by-matrix cases
  (the row-vector-by-matrix case is one output row and stays serial), and
  `linalg::%la-matmul-nd` (the stacked product: rows counted across the whole stack,
  `batches * n` of them, a batch boundary being just another row boundary to the split).
- **Never a reduction** (`sum` / `dot` of two vectors / `norm` / `mean`, the axis folds):
  there the fold order IS the value. Never the element-wise kernels either: they are
  bandwidth-bound and a dispatch costs more than they do.
- The serial path is untouched: with the flag off, a `--simd` build binds the same bridge
  entries and computes what it computed before; the parallel entries are four more
  methods on the same bridge (`simdMatvecParallel`, `simdMatvecIntoParallel`,
  `laDotParallel`, `laMatmulNdParallel`), and the emitted class differs from a `--simd`
  class by those method names alone.

## The knob

- `--parallel` is value-less (`CliOptions.noValueKeys` -- the `--simd` dead-flag lesson,
  `CliOptionsTest`). It requires `--simd` and is a hard error without it, on the
  interpreter, the REPL and the compiler alike (`RontoLispCli.requireSimdForParallel`,
  `JvmLispCompiler`'s 7-argument constructor). It is a hard error on a `.wasm` output:
  neither wasm backend has threads, and a silent no-op is what an acceleration flag exists
  to prevent. Threads never appear behind a user's back: a `vec:` program that silently
  occupied 20 cores would change CPU accounting, container limits and the meaning of every
  benchmark in this repo.
- `RONTOLISP_THREADS` is the thread count, the CALLING thread included (default
  `availableProcessors`; `1` = serial; a malformed value warns once on stderr and uses the
  default). Mirrors how `.kb/linalg-blas.md` defers to `OPENBLAS_NUM_THREADS`. Read once,
  at the first call worth splitting, by both twins (`JvmSimdVectorTemplate.parallelThreads`,
  `eval/SimdParallel.threads`).
- The pool: `threads - 1` daemon worker threads (`rontolisp-parallel-N`), started lazily at
  that first call and never before -- a program that makes no call above the threshold
  starts no thread, and a `.class` built with the flag has no shutdown path to worry
  about (daemon threads park when idle). They are NOT a `ForkJoinPool` -- see "The shape"
  for why. On the JVM the dispatch lives in the embedded bridge (`JvmSimdVectorTemplate`,
  a single class: written with lambdas, `LockSupport`, `AtomicInteger`s and an `Object[]`
  job record, because the single-blob injection can carry no nested class --
  `.kb/template-class-embedding.md`); on the interpreter it is `eval/SimdParallel`, the
  twin, operation for operation, with a real `RowKernel` interface and a `Job` record
  since `eval` may nest types. One call at a time (the dispatch is synchronized): a second
  calling thread waits, which is right when one call already uses every core.
- `--gpu`: the parallel lanes sit strictly BELOW the device decision. `compileGpuMatvec`
  and `JvmLinalgKernelCompiler` emit device -> lane-or-defun chains whose lane rung is now
  the parallel entry; the device attempt, and so `CudaResidency` (not thread-safe), runs
  on the calling thread only, and only what the device declines is split. The interpreter
  install order is unchanged (`LinalgGpu.installVec` wraps whatever `VecSimd` bound, which
  is now the parallel native). Pinned by
  `JvmSimdParallelCompilerTest.underGpuTheParallelLanesSitBelowTheDeviceDecision`.

## The shape, decided by measurement

Seven probes (`.todo/478-the-simd-kernels-never-use-more-than-one-core/GemvPoolProbe*.java`,
recoverable through `.todo/.history.md`; f32 GEMV at llama2's shapes, the GB10's 10
Cortex-X925 + 10 Cortex-A725, medians, results asserted bit-identical every time). The
first five chose a shape over a `ForkJoinPool`; the sixth, run after that shape had landed
in situ at 1.06x where the probes said 1.8-3.8x, found why and replaced it:

1. **Who splits decides everything** (probes 2-3). `pool.invoke(task)` from an outside
   thread hands ONE task to ONE worker, which splits it alone while the caller parks:
   0.15-0.9x at 768x288 however the task is shaped. The caller halving the row range,
   handing the right halves to the pool and computing the left-most leaf itself -- the
   gist's parallel-stream shape -- is 1.4-1.9x there; the caller must also SPIN for the
   rest rather than `join()` (a park/unpark round trip per call: 0.8-1.0x vs 1.8-2.3x).
2. **Back-to-back calls are not the workload** (probe 6, the decisive one). Every probe
   so far ran GEMVs in a tight loop, so the pool's workers never went idle. A decode loop
   runs ~50-200 us of boxed Lisp between GEMVs, and a `ForkJoinPool` worker parks within
   microseconds of going idle -- so in situ EVERY dispatch paid the unpark chain, and the
   shape that measured 1.8x at 768x288 back to back measured 0.5-0.9x with a 20-200 us
   gap, exactly the llama2 result (234 vs 221 tok/s, and `RONTOLISP_THREADS=10` slower
   than serial). The fix is what every BLAS does: workers that SPIN for the next call
   (`Thread.onSpinWait` on an epoch) and park only after `SPIN_NANOS` = 1 ms idle, with
   the rows claimed in grain-sized leaves off one `AtomicInteger` by caller and workers
   alike (work claiming, so a slow A725 core simply takes fewer leaves). With a gap the
   same 768x288 GEMV runs in 5.5 us against 26 us serial -- 4.7-5.6x at every gap length
   -- and the head 3x. The cost is honest and documented: while a loop of products runs,
   the workers are busy cores; a millisecond after the last call they sleep.
3. **The dispatch floor is ~3 us**, so the threshold is **2^15 multiply-adds**
   (`PARALLEL_MIN_WORK` / `SimdParallel.MIN_WORK`; probe 7: 288x288 = 83K MACs 2.1-3.2x,
   144x288 = 41K 1.6-2.9x, and 128x128 = 16K is ~2 us of serial work, below the floor),
   at least two rows, with `threads - 1` workers plus the caller (probe 3: equal or
   better than `threads` workers at every shape, the caller being a worker of its own),
   a **grain of 2^13 multiply-adds per leaf and at most 4 leaves per thread** (probe 5: a
   fixed 2^13 grain alone puts 1100 leaves on the claim counter at the head, 3.3x vs
   3.8x capped; a fixed 2^14 grain loses at 768x288). One threshold for both widths: f64
   is ~2x the per-MAC cost, so it crosses over earlier and the bound is conservative there.
4. **The job is one object read once per call by each worker** (`Object[]` in the
   template, a `Job` record in `SimdParallel`): the kernel, the shape and the call's OWN
   claim and pending counters. A worker that is late to a call therefore cannot mix one
   call's kernel with the next call's counters -- with shared counters, a worker
   descheduled between its last leaf and its final (empty) claim would have claimed and
   decremented the NEXT call's rows under the OLD kernel, and the caller would have
   returned with rows missing.
5. **Parking is a Dekker handshake**: a worker publishes its `parked` flag, looks at the
   epoch once more, then parks; the caller bumps the epoch, then scans the flags and
   unparks the set ones. One side always sees the other; a stale permit costs a worker one
   extra look.

The shipped shape, a GEMV with `gap` us of other work between calls (probes 6/7, 20 threads):

| shape | serial us | gap 0 | gap 50 us | gap 150-200 us | the `ForkJoinPool` shape at gap 50 |
|---|---|---|---|---|---|
| 144x288 | 5.0 | 1.7x | 1.6x | 1.7x | 0.1x |
| `wq/wk/wv/wo` 288x288 | 9.9 | 2.1x | 3.2x | 2.7x | 0.3x |
| `w1/w3` 768x288 | 26 | 3.0x | 4.7x | 4.9x | 0.7x |
| `w2` 288x768 | 32 | 5.6x | 5.6x | 5.6x | 0.8x |
| classifier 32000x288 | 1190 | 3.1x | 2.8x | 3.0x | 2.9x |

**The ceiling is memory, not scheduling**: ~3x on a 37 MB matrix with 20 cores is the
GB10's bandwidth, and the feed-forward shapes go further because their matrices fit in
the caches the workers already hold them in. The gist's whole-program 1.8x (297 -> 535
tok/s) is the number to expect from a decode loop, not the core count.

## In situ (`examples/llama2`, stories15M, the 222-token `-t 0` story, medians of three)

| JVM class, 20 cores | tok/s | ms/token | story |
|---|---|---|---|
| `--simd` | 221 | 4.5 | byte-identical |
| `--simd --parallel` (the first, `ForkJoinPool` shape) | 234 | 4.3 | byte-identical |
| `--simd --parallel` (spin-then-park, before the workers yielded) | 267 (330 at `RONTOLISP_THREADS=10`) | 3.7 | byte-identical |
| **`--simd --parallel`** (shipped: spinning workers yield every 64 spins) | **319** (330 at `RONTOLISP_THREADS=10`, 326 at 16) | 3.1 | byte-identical |
| `--gpu --simd` | 278 | 3.6 | byte-identical |
| `--gpu --simd --parallel` | 265 | 3.8 | byte-identical |
| interpreter `--simd` / `--simd --parallel` | 44 / 44 (the tree walk around the GEMVs dominates; the flag buys nothing there, like `--gpu`) | | byte-identical |

Three things the in-situ numbers say that the probes could not:

- **The yield matters as much as the spin.** 19 pure spinners on a 20-core box crowd
  out the caller, the JIT and the GC: 267 tok/s at 20 threads but 330 at 10. With
  `Thread.yield()` every 64 spins (free on an idle core, a hand-over on a busy one) the
  default `availableProcessors` is within noise of the best count (319 vs 330), and the
  remaining difference is the GB10's ten small cores.
- **`--gpu --simd --parallel` is slower than either flag alone on llama2** (265 vs 278 /
  319): the device takes the big GEMVs, the spinning workers compete with the driver's
  threads for the cores, and the lanes are left with the 288x288 projections. The chain
  is correct and pinned; the combination is documented as not a win for this program.
- **The gate's second half is NOT met by this item alone**: 319 on 20 threads against the
  gist's 535 on 20. The GEMVs are down from ~2.4 ms to ~0.7 ms of a token; the ~2.2 ms
  of boxed attention / RoPE / KV-cache glue is `.todo/457`'s and is now two thirds of the
  token. The first half is: 319 >= 260, story byte-identical on all four backends with
  and without the flag (`ExamplesE2eTest`, `parallel: true` on both llama2 entries).

## `linalg:` GEMM against `--blas` (phase 3)

`linalg:dot` over two n x n single-float matrices, JVM class, ms per product (medians of 7,
`.todo/478-.../` scratch `gemm-bench.lisp`), OpenBLAS 0.3 (`libopenblas.so.0`, pthread
build, `OPENBLAS_NUM_THREADS` default = 20):

| n | `--simd` | `--simd --parallel` (20 / 10 threads) | `--blas` (20 / 10 threads) |
|---|---|---|---|
| 128 | 0.20 | 0.06 / 0.04 | 0.04 / 0.06 |
| 256 | 1.35 | 0.20 / 0.20 | 0.15 / 0.15 |
| 512 | 10.6 | 1.4 / 1.6 | 0.6-0.8 / 0.4 |
| 1024 | 84.5 | 15.0 / 13.5 | 4.0-4.5 / 3.5 |

The interpreter's `--simd --parallel` lands on the same figures (13.5 ms at 1024). So the
row split buys the GEMM 5.6-7.6x over the serial lanes, and **a tuned threaded BLAS still
wins it by 1.3-3.8x** -- a blocked SGEMM against an `ikj` lane loop, as `.todo/478`
phase 3 anticipated ("if it does, say so and stop"). `--blas` therefore stays the answer
for a program that is GEMM-bound and has a library, `--parallel` the answer where there is
none, for the GEMV (`vec:matvec`, outside `--blas`'s set, `.todo/471`), and for the
stacked product. The `--blas` results differ from `--simd`'s in the last digits (the
library's fold order), the parallel ones do not.

## Tests

| what | where |
|---|---|
| JVM: bit-identity at both widths above the threshold (GEMV, `-into`, `dot` M.v and M.M, the stacked product), below it, the emit gate (the four names and nothing else), `--parallel` without `--simd` refused, the `--gpu` chain's lane rung | `codegen/jvm/JvmSimdParallelCompilerTest` |
| interpreter: the dispatch covers every row once and joins, a failing leaf surfaces on the caller, the threshold, bit-identity of the same members, the flag inert without `--simd` | `eval/SimdParallelTest` |
| the flag is value-less; refused without `--simd`, refused on `.wasm`, binds the parallel entries on `.class` | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |
| the llama2 story byte-identical under `--simd --parallel` on the interpreter and the JVM (`parallel: true` in `examples/examples.yaml`) | `e2e/ExamplesE2eTest` |
