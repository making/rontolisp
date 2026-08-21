# `--blas`: the `linalg:` matrix product on a tuned CBLAS

The second acceleration flag over the `linalg:` interception seam (todo-470, 2026-08-20).
Read `.kb/linalg-simd.md` first: this reuses that file's declined-input protocol verbatim
and only adds an attempt ahead of it. `.kb/linalg.md` has the semantics of the library
being accelerated.

Two backends, one per interception mechanism -- the same two `--gpu` reaches, and for the
same reason (the foreign function API). `--gpu` (`.kb/gpu.md`) has since landed on BOTH of
them and goes AHEAD of this one, so the device is asked first and declines here:

| backend | interceptor | binding |
|---|---|---|
| interpreter (`prog.lisp --blas`, native binary included) | `eval/LinalgBlas` (re-`defineFunction`) | `eval/LinalgBlasKernels` (java.lang.foreign) |
| JVM (`-o Prog.class --blas`) | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmBlasTemplate` (the embedded bridge) |

WASM has no FFM, so `--blas` with a `.wasm` output is a hard error rather than a silent
no-op (`RontoLispCli.compileRecorded`). The two WASM backends keep `--simd`.

The user-facing description lives in `doc/{en,ja}/guides/simd-acceleration.md` ("Accelerating
the matrix product with a tuned BLAS"). Keep the intercepted set, the install
recommendation, the thread note and the precision contract in sync with it.

## The decision this item is built on

**A tuned BLAS is RECOMMENDED, never required** (user, 2026-08-20). Nothing is bundled,
nothing is downloaded, and a machine without one runs the same programs to the same output.
There is ONE mechanism -- find a tuned CBLAS, verify it is tuned, use it, otherwise decline
to the kernel we already have -- and the platforms differ only in what the search finds:
macOS finds Accelerate with the user doing nothing, a Linux user is TOLD in the docs to
install OpenBLAS and gets 5-20x for it. This is `--gpu`'s posture toward a GPU, one layer
down.

## Why it is its own flag rather than what `--simd` means

`--simd`'s three backends agree bit for bit with each other and, at `#d`, with the scalar
defun, because we wrote all three kernels. A vendor BLAS blocks and reorders its reduction,
so **which library and which VERSION is installed becomes part of the numerical answer**, at
`linalg`'s DEFAULT width. Folding that into `--simd` would silently change what an existing
`--simd` build computes. So it is a separate opt-in, and the composition is a chain:

```
--blas --simd   ->  library gemm -> lane kernel -> scalar linalg.lisp defun
--blas          ->  library gemm ->               scalar linalg.lisp defun
--simd          ->                 lane kernel -> scalar linalg.lisp defun
```

Each link is the SAME partial-kernel protocol: return the null sentinel for an input it
declines, and the layer below answers. On the interpreter that is install order
(`LinalgBlas.install` runs LAST, capturing whatever `linalg:dot` was bound to); on the JVM it
is `JvmLinalgKernelCompiler.compile`, which evaluates each argument form ONCE into a temp and
emits one `IFNONNULL` per attempt to a common end label. The temps are what make a chain of
any length safe -- recompiling the argument forms would repeat their side effects.

`JvmLinalgSimdCompiler` was renamed `JvmLinalgKernelCompiler` for this: it is the one
`linalg:` call-site compiler, and it now emits a chain of up to two bridges. The two bridges
are separate embedded classes on purpose -- `--blas` must not drag in the incubator Vector
API, which would make the emitted class need `java --add-modules jdk.incubator.vector` to
run (pinned by `theTwoFlagsAreOrthogonalAndEmbedTheirOwnBridges`).

## The intercepted set: the product, and nothing else

`linalg:dot` only, in its three matrix shapes -- matrix x matrix (`cblas_dgemm` /
`cblas_sgemm`), matrix x vector and vector x matrix (`cblas_dgemv` / `cblas_sgemv`, the
second with `CblasTrans`). `linalg:matmul` at rank <= 2 and `linalg:solve` are accelerated
TRANSITIVELY, because their `linalg.lisp` bodies call `linalg:dot`.

Everything else declines, by measurement rather than by staging:

- **The memory-bound members would gain nothing.** `sum`, a vector-vector `dot`, `axpy`:
  the library call cannot beat a lane loop over the same bytes, and `--simd` already covers
  them.
- **The stacked rank-3 product (`%la-matmul-nd`) is a separate interception.** `--simd`
  gained one in todo-467 (2026-08-20); `--blas` deliberately did NOT, and the reasoning was
  written down there rather than deferred again -- see "Why `--blas` stopped at `dot`"
  below.
- **`worth(n, m, p)` = `n*m*p >= 64`.** Measured on an M4 Max, a critical downcall floors at
  ~30 ns and the crossover against a plain JIT-warm `ikj` triple loop is at about 4x4x4
  (n=4: 0.039 us against 0.046; n=8: 0.071 against 0.289; n=32: 0.44 against 16.9).

## Why `--blas` stopped at `dot` (todo-467, 2026-08-20)

todo-467 intercepted `linalg::%la-matmul-nd` for `--simd` and asked whether the same member
should get a `cblas_?gemm` per matrix over this seam. It should, eventually -- a stacked
product IS a batch of gemms, the `gemm`/`gemmF` entry points here already take element
OFFSETS, and on a transformer that is where the time is. It was NOT done, on a budget, and
this is what the next person needs:

- **It is not "a handful of lines over the bridge that already exists".** `--blas` is built
  around exactly one member: `JvmLinalgBlas.handles` compares against one name,
  `JvmBlasRuntimeBuilder` registers one `ops` key (`DOT`), and
  `JvmLinalgKernelCompiler.compile` emits the blas attempt with that key hardcoded. A
  second member means a member->key map on all three, plus the emit-gate scan.
- **The template cannot borrow the batch walk.** `JvmBlasTemplate`'s bytes must stand
  alone once embedded (no reference to any other rontolisp class), so it would need its own
  copy of `laDims` / `laBcastShape` / `laBatchStrides` / the odometer -- ~120 lines
  duplicated from `JvmSimdVectorTemplate`, kept in lockstep by hand, in a file whose whole
  discipline is already "mirrored, change them together". (todo-123 phase 2 found the way
  out of that for `--gpu`: the blob can carry a CLOSURE of classes, renamed by one prefix
  rule, so the compiled backend runs the library's own bytes instead of a copy --
  `.kb/gpu.md`, "The JVM backend". Nothing here has been rebuilt on it; if this member set
  ever grows, that is the mechanism to grow it on.)
- **`worth(n, m, p)` has to be re-decided per batch.** The existing threshold is one
  product's flops; for a batch the right predicate is the PER-MATRIX work (a downcall per
  matrix, `batches` of them), and whether `batches` small enough to lose to the lane kernel
  should decline the whole call. That is a measurement, not a transcription.
- **The precision contract grows a case.** `--simd`'s batched kernel is a per-batch
  `linalg:dot` exactly; a library gemm per batch is "close to", the same way this file
  already says for rank 2 -- and the docs section here names `linalg:dot` specifically.

None of that is hard. It is simply a second item, and the `--simd` interception is the one
that had to land (it reaches the WASM backends, which `--blas` never will).

## Identifying a TUNED library is the load-bearing part

"Found a CBLAS" is not the useful predicate. The netlib REFERENCE implementation exports the
same symbols and measured **7-8 GFLOP/s** on a DGX Spark, where the same machine's `--simd`
matmul does n=512 f64 in 21.2 ms against the reference BLAS's 35.3: **binding what was found
would have been a silent 1.6x REGRESSION at linalg's default width.** Being slower than the
unaccelerated build is the one way this feature can do harm.

The soname proves nothing either -- Debian's `libblas.so.3` is an `update-alternatives`
symlink that points at OpenBLAS when one is installed and at the reference when not.

So the rule is **marker symbols**: a candidate is accepted only if it is Accelerate (by
framework path) or exports a symbol a tuned implementation has and the reference does not
(`openblas_get_config`, `mkl_get_version` / `MKL_Get_Version`, `bli_info_get_version_str`,
`ATL_buildinfo`, `nvpl_blas_get_version`, `armpl_get_version`). The marker is cheap and
deterministic and costs no startup benchmark; the throughput measurement that established
the rule lives in `.todo/123-gpu-acceleration/AccelerateProbe.java` and stays runnable, so
the rule can be re-checked on new hardware. `RONTOLISP_BLAS` overrides both the search and
the check (the user has asserted it); `RONTOLISP_BLAS_VERBOSE=1` prints what was bound.

The candidate list, the marker list, `MIN_WORK` and `CRITICAL_FLOP_CEILING` are MIRRORED in
`eval/LinalgBlasKernels` and `codegen/jvm/JvmBlasTemplate` -- the JVM template's bytes must
stand alone once embedded, so it cannot call the eval class. Change them together. This is
the duplication `--gpu`'s JVM half was built to avoid (`.kb/gpu.md`); the same treatment
would fit here, and has not been applied.

## No copy: `Linker.Option.critical` takes heap segments

`linalg`'s arrays are Java heap `double[]` / `float[]`, and FFM cannot normally hand a heap
array to a native call -- which is why todo-470 listed the heap -> native copy as a
structural cost and shared it with todo-123 phase 3. It is not one. A `critical(true)`
downcall accepts heap `MemorySegment`s, and `MemorySegment.ofArray(a).asSlice(off * 8)`
carries the compiled representation's dimension header out of the picture for free. Measured
(M4 Max, f64, ms per n x n gemm):

| n | copy into a confined arena | critical, no copy |
|---|---|---|
| 32 | 0.0241 | **0.0028** |
| 128 | 0.0297 | **0.0127** |
| 512 | 0.5604 | **0.3269** |
| 1024 | 3.4540 | **2.8693** |
| 2048 | 23.6543 | 22.3545 |

The cost is that a critical call does NOT transition the thread to native, so the VM cannot
reach a safepoint while it runs. Hence the hybrid: **`2*n*m*p <= 2^32` goes critical, above
that the operands are staged in a confined arena.** 2^32 flops is ~5 ms on a tuned library,
and from there up the staging copy is a few percent of the call (5% at n=2048), so the
bounded GC latency is nearly free exactly where it starts to matter. A gemv is always
critical: it is memory-bound, so its duration is bounded by the operand it was handed.

## What it is worth

Apple M4 Max, macOS 26.3.1, Accelerate, one `#d` n x n matmul, ms per call. `--simd` is
post-todo-469:

| n | interpreter scalar | interpreter `--simd` | interpreter `--blas` | JVM `--simd` | JVM `--blas` |
|---|---|---|---|---|---|
| 128 | 1152 | 0.550 | **0.040** | 0.350 | **0.026** |
| 256 | -- | 2.500 | **0.113** | 2.590 | **0.097** |
| 512 | -- | 20.667 | **0.380** | 21.100 | **0.450** |
| 1024 | -- | -- | **3.100** | 181.500 | **3.167** |

So 6-54x over `--simd`, and the INTERPRETER lands on the JVM's number: once the product is a
library call, the interpreter's per-call overhead is all that is left of the gap between the
two backends. On Linux, OpenBLAS 0.3.26 on a 20-core Grace machine measured 20x `--simd`
threaded and 5.2x single-threaded (f64, n=512: 21.4 ms against 1.073 / 4.137).

Re-run both halves together, never one against a stale copy of the other:
`.todo/123-gpu-acceleration/AccelerateProbe.java` for the library and a `linalg:matmul` loop
for the `--simd` column.

## The two contracts

1. **Precision.** An accelerated product is CLOSE to the scalar defun, not equal to it, at
   both widths. Over inputs exact at the operand width (integers, powers of two) the results
   still match exactly -- which is what lets the cross-backend tests assert equality; over
   inexact ones they differ in the last few ulps (measured < 1e-12 relative over a 64-long
   fold, pinned at that tolerance rather than at an equality because the library and its
   version decide the figure). The scalar `linalg.lisp` defun remains the cross-backend
   oracle, and `--blas` stays out of `ci-spec.yaml`.
2. **Threads.** A tuned BLAS is multi-threaded and rontolisp is not: one `linalg:matmul` may
   occupy every core. That is most of the Linux figure above. The docs say so and name
   `OPENBLAS_NUM_THREADS` / `MKL_NUM_THREADS` / `VECLIB_MAXIMUM_THREADS` rather than fighting
   them.

## Tests

| what | where |
|---|---|
| interpreter, needs a library on the machine (`@EnabledIf`) | `eval/LinalgBlasTest` |
| interpreter, must hold on EVERY machine | `eval/LinalgBlasDeclineTest` |
| JVM emit gate + accelerated + declined + arg-evaluated-once | `codegen/jvm/JvmLinalgBlasAccelCompilerTest` |
| the flag is value-less (the `--simd` dead-flag lesson) | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

The dead-flag guard matters more here than anywhere: every numeric assertion in these files
would pass on the scalar defun. `#'linalg:dot` printing `#<function LINALG:DOT>` (interpreter)
and the bridge name appearing in the class bytes (JVM) are the assertions that fail when the
flag is dead.

Native image needs two things: the `JvmBlasTemplate.class` resource entry (already in
`resource-config.json`, beside the `--simd` one) and `--enable-native-access=ALL-UNNAMED`,
which the `native` profile already passes. The exec jar carries
`Enable-Native-Access: ALL-UNNAMED` in its manifest; a compiled `.class` does not, so running
one prints the JVM's restricted-method warning unless the user passes the flag -- which the
docs say.
