# `--blas`: the matrix product on a tuned CBLAS

The second acceleration flag over the `linalg:` interception seam (todo-470, 2026-08-20),
extended to the `vec:` GEMV pair on 2026-09-02 (todo-471).
Read `.kb/linalg-simd.md` first: this reuses that file's declined-input protocol verbatim
and only adds an attempt ahead of it. `.kb/linalg.md` has the semantics of the library
being accelerated.

Two backends, one per interception mechanism -- the same two `--gpu` reaches, and for the
same reason (the foreign function API). `--gpu` (`.kb/gpu.md`) has since landed on BOTH of
them and goes AHEAD of this one, so the device is asked first and declines here:

| backend | interceptor | binding |
|---|---|---|
| interpreter (`prog.lisp --blas`, native binary included) | `eval/LinalgBlas` (re-`defineFunction`: `install` for `linalg:`, `installVec` for `vec:`) | `eval/LinalgBlasKernels` (java.lang.foreign) |
| JVM (`-o Prog.class --blas`) | `codegen/jvm/JvmLinalgKernelCompiler` (`linalg:` call site) and `JvmSimdCompiler.compileMatvecChain` (`vec:`) | `JvmBlasTemplate` (the embedded bridge) |

WASM has no FFM, so `--blas` with a `.wasm` output is a hard error rather than a silent
no-op (`RontoLispCli.compileRecorded`). The two WASM backends keep `--simd`.

The user-facing description lives in `doc/{en,ja}/guides/blas-acceleration.md` (its own
page, split out of the `--simd` guide). Keep the intercepted set, the install
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

## The intercepted set: the product, in both packages that have one

Three members, no more:

- **`linalg:dot`**, in its three matrix shapes -- matrix x matrix (`cblas_dgemm` /
  `cblas_sgemm`), matrix x vector and vector x matrix (`cblas_dgemv` / `cblas_sgemv`, the
  second with `CblasTrans`). `linalg:matmul` at rank <= 2 and `linalg:solve` are
  accelerated TRANSITIVELY, because their `linalg.lisp` bodies call `linalg:dot`.
- **`vec:matvec`** and **`vec:matvec-into`** (todo-471, 2026-09-02) -- one
  `cblas_?gemv` with `alpha = 1`, `beta = 0`, `CblasNoTrans`. The `-into` form is the
  better fit of the two: gemv writes into a caller-supplied `y`, so the interception drops
  the result allocation as well as the loop. `vec:mean` / `vec:norm` are not intercepted
  and are not accelerated by this flag either -- they fold, they do not multiply.

The `vec:` half is what makes the flag reach the programs it exists for. Before it,
**not one of the numeric examples this project ships was touched by `--blas`**:
`simd-dot`, `simd-gemv`, `tiny-llm` and `llama2` are all `vec:` programs, and an LLM
decode is GEMV from end to end -- one weight matrix times one activation vector, over and
over.

Everything else declines, by measurement rather than by staging:

- **The memory-bound members would gain nothing.** `linalg:sum`, a vector-vector
  `linalg:dot` / `vec:dot`, `axpy`, every element-wise `vec:` kernel: the library call
  cannot beat a lane loop over the same bytes, and `--simd` already covers them. This is
  the claim that keeps `vec:dot` out even though `vec:matvec` beside it is in.
- **The stacked rank-3 product (`%la-matmul-nd`) is a separate interception.** `--simd`
  gained one in todo-467 (2026-08-20); `--blas` deliberately did NOT, and the reasoning was
  written down there rather than deferred again -- see "Why `--blas` stopped at `dot`"
  below.
- **`worth(n, m, p)` = `n*m*p >= 64`.** Measured on an M4 Max, a critical downcall floors at
  ~30 ns and the crossover against a plain JIT-warm `ikj` triple loop is at about 4x4x4
  (n=4: 0.039 us against 0.046; n=8: 0.071 against 0.289; n=32: 0.44 against 16.9).

## `vec:` gets its own guarded call site, not a rung inside the `--simd` bridge

The `vec:` half raised one real design question (todo-471). The `linalg:` kernels are
PARTIAL -- they return the null sentinel for anything they decline and the call site falls
through -- but the `vec:` kernels are TOTAL: they accept packed float arrays of one width
and SIGNAL on anything else, so `JvmSimdCompiler` emits a bare `INVOKESTATIC` for them and
`VecSimd` installs a native that signals. There is no guard to hang a library attempt on.
Two ways out, and they differ in what `--blas` MEANS:

1. Put the attempt INSIDE the lane bridge (`simdMatvec` tries the library, then its own
   loop). Smaller, and wrong. It makes the `vec:` half of `--blas` require `--simd`: a
   `--blas`-only build would run the scalar `vec.lisp` defun for a GEMV, which is the one
   thing this flag must never do, and undiscoverably so. It also drags FFM into the
   `--simd` bridge and the incubator Vector API into the `--blas` one, destroying the
   orthogonality `JvmBlasRuntimeBuilder` exists for -- a `--blas` class would then need
   `java --add-modules jdk.incubator.vector` to run.
2. **Give `vec:matvec` / `vec:matvec-into` a guarded CHAIN of their own**, the shape
   `JvmLinalgKernelCompiler` already emits for `linalg:`: device -> library -> lane kernel
   -> spliced defun, over one set of temps, each rung declining with `null` into the next
   and the bottom rung total. **This is what was done.**

It duplicates no seam. `--gpu` had already built exactly this for `vec:matvec`
(`JvmSimdCompiler.compileGpuMatvec`, the one device member outside `linalg:`), so the work
was to GENERALIZE that one call site into `compileMatvecChain` -- gpu rung optional, blas
rung optional, simd rung optional, defun always -- and route both members through it.
`JvmExprCompiler` claims the site when `--blas` emitted its bridge (either member) or
`--gpu` emitted its own (the allocating form only); a `--simd`-only build keeps the bare
`INVOKESTATIC` it always emitted, byte for byte. The interpreter is the same story one
layer up: `LinalgBlas.installVec` runs after `VecSimd.install` and before
`LinalgGpu.installVec`, so the install order is device -> library -> lanes -> defun there
too.

The emit gate moved with it: `JvmLispCompiler` scans for
`JvmLinalgBlas.qualifiedMembers()` -- `linalg:dot`, `vec:matvec`, `vec:matvec-into` --
rather than the product alone. A gate on `linalg:dot` would embed no bridge for exactly
the programs the flag is for.

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
  should decline the whole call. That is a measurement, not a transcription. (`--gpu` took
  this member on 2026-08-21 and its answer does NOT carry over, for the reason this bullet
  gives: a device runs the whole stack in ONE launch, so its threshold is the TOTAL work
  and a batch of tiny matrices still pays. A library gemm per matrix is `batches`
  downcalls, so the per-matrix rule stands here.)
- **The precision contract grows a case.** `--simd`'s batched kernel is a per-batch
  `linalg:dot` exactly; a library gemm per batch is "close to", the same way this file
  already says for rank 2 -- and the docs section here names `linalg:dot` specifically.

None of that is hard. It is simply a second item, and the `--simd` interception is the one
that had to land (it reaches the WASM backends, which `--blas` never will). `--gpu` has
since taken the member (`.kb/gpu.md`), so on the interpreter and the JVM a stacked product
already has a non-CPU path; what `--blas` would add is a rung between the device and the
lane kernel, for the stacks the device declines.

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

### The GEMV, and what the hardware decides (2026-09-02)

`.todo/471-.../GemvProbe.java` is the gemv counterpart -- it times `cblas_?gemv` against
the LANE kernel the emitted bridge actually runs (`FloatVector.SPECIES_128` for `#f`,
`DoubleVector.SPECIES_PREFERRED` for `#d`, both folding with `mul().add()`), so the ratio
is the one the flag delivers rather than a ratio over a scalar loop. Its directory
outlived the item that produced it, the way `.todo/123-gpu-acceleration/` did:
`RONTOLISP_BLAS` (or `PROBE_BLAS`) names the library, so it runs on any machine.
**Two machines, and they do not tell the same story:**

| rows x cols | M4 Max / Accelerate | dorian / OpenBLAS, 1 thread | dorian / OpenBLAS, 64 threads |
|---|---|---|---|
| 256x256 (`simd-gemv`) | 6.3x | 1.69x | 1.35x |
| 288x288 (llama2 stories15M attention) | 7.0x | 1.81x | 2.01x |
| 288x768 (its FFN up-projection) | 9.5x | 1.92x | 7.63x |
| 768x288 (its FFN down-projection) | 8.0x | 1.83x | 8.63x |
| 4096x288 | 8.0x | 1.73x | 9.58x |
| 2048x2048 | 8.2x | 1.97x | 18.65x |
| f64 256x256 | 6.9x | 1.34x | **0.84x** |
| f64 512x512 | 7.8x | 1.36x | 7.32x |
| f64 2048x2048 | 8.0x | 1.21x | 15.54x |

dorian is a 64-core Intel Xeon E5-2697A v4 with OpenBLAS 0.3.x from Debian. The M4 Max
column is todo-471's original table (Accelerate, 2026-08). **The x86-64 single-thread
ratio is a third of the Apple one**, which is what the lane kernel's pinned
`SPECIES_128` costs it on Apple and does not cost it here: 128-bit lanes are all NEON has,
while an AVX2 machine gives the JIT 256-bit registers for the same source, so the kernel
being beaten is twice as fast to begin with. **The premise -- gemv is worth binding --
holds on both; the 6-9x figure is Apple's, not the feature's.**

The 64-thread column is the trap. A gemv is memory-bound and short, so a threaded library
pays a barrier per call that the call itself cannot amortize: at f64 256x256 it is a net
LOSS, and end to end it is far worse than the table suggests. llama2 stories15M on the JVM
backend, 150 tokens, greedy:

| build | tok/s |
|---|---|
| `--simd` | 101.8, 110.1 |
| `--simd --blas`, `OPENBLAS_NUM_THREADS=1` | 123.7, 120.8 |
| `--simd --blas`, 64 threads (the default) | 16.0 |

So on this machine the flag is **1.15x** where it is capped and **5.4x SLOWER** where it
is not. The threads contract below is not advice for polite multi-tenancy; for a decode
loop it is the difference between a win and a rout, and the docs say so.

The interpreter tells the same story from further away, because there the GEMV is a small
share of a much slower loop. `simd-gemv` (256x256 `#f`, 100 steps), `java -jar`: 8964 ms
scalar -> 187 ms `--simd` -> 131 ms `--simd --blas` (1 thread) -> 371 ms `--simd --blas`
(64 threads). `--blas` WITHOUT `--simd` is 629 ms: the GEMV is a library call, but
`vec:dot` and `vec:scale` beside it are still interpreted defuns, which is most of what is
left.

On the NATIVE BINARY the same program is 190 ms under `--blas` alone, 47 ms under `--simd`
and 49 ms under `--simd --blas` -- **a tie**. At this shape the native binary's lane kernel
is already at library speed, so the flag buys nothing there and the reason to pass it is
the bigger shapes, not this one. Which is the general shape of the finding: `--blas` is
worth passing for a GEMV when the matrix is large, the library is capped to one thread,
and the rest of the loop is compiled. Two of those three failing is enough to make it a
wash, and all three failing makes it a loss.

## The two contracts

1. **Precision.** An accelerated product is CLOSE to the scalar defun, not equal to it, at
   both widths. Over inputs exact at the operand width (integers, powers of two) the results
   still match exactly -- which is what lets the cross-backend tests assert equality; over
   inexact ones they differ in the last few ulps (measured < 1e-12 relative over a 64-long
   fold, pinned at that tolerance rather than at an equality because the library and its
   version decide the figure). The scalar `linalg.lisp` / `vec.lisp` defun remains the
   cross-backend oracle, and `--blas` stays out of `ci-spec.yaml`.

   **The pinned example outputs were the risk, and they did not move** (2026-09-02). The
   `vec:` half reorders an `#f` reduction on exactly the shapes whose DERIVED integers the
   examples pin -- `simd-gemv`'s argmax indices, `tiny-llm`'s token ids, `llama2`'s story
   -- and the probe's max relative difference on the classifier-head shape is 5.5e-3,
   easily enough to move an argmax. Every one was run rather than assumed: `simd-gemv`
   prints the same ten indices and the same step-100 index under `--blas`, `--simd
   --blas`, and both thread settings; `tiny-llm` the same nine tokens; `llama2` on
   stories260K the same story, and on stories15M -- 288-wide, with a 32000x288 classifier
   head folded every token -- byte-identical output at 150 tokens between `--simd` and
   `--simd --blas`. So the examples were NOT loosened and `--blas` did not need the
   `--gpu` posture of "not compared against the other backends". If a future machine or
   library version does move one, THAT is the fix -- not a looser expectation.
2. **Threads.** A tuned BLAS is multi-threaded and rontolisp is not: one `linalg:matmul` may
   occupy every core. That is most of the Linux figure above. The docs say so and name
   `OPENBLAS_NUM_THREADS` / `MKL_NUM_THREADS` / `VECLIB_MAXIMUM_THREADS` rather than fighting
   them. **For the GEMV half this is not a courtesy, it is a correctness-of-the-claim
   issue**: a decode loop is thousands of short memory-bound calls, the per-call barrier
   swamps them, and on a 64-core machine the flag turns a 1.15x win into a 5.4x loss
   (measured above). rontolisp does not set the variable -- a library's thread pool is the
   user's to size, and overriding it from inside would be a worse surprise than the one it
   fixes -- so the docs lead with it on this page. Since todo-478 `--simd --parallel` (`.kb/simd-parallel.md`) threads the lane GEMM
   too, and the library still wins it by 1.3-3.8x at n = 128..1024 on the GB10 (a blocked
   SGEMM against an `ikj` lane loop), so this flag stays the answer where a library exists.

## Tests

| what | where |
|---|---|
| interpreter, needs a library on the machine (`@EnabledIf`) | `eval/LinalgBlasTest` (both packages) |
| interpreter, must hold on EVERY machine | `eval/LinalgBlasDeclineTest` (both packages) |
| JVM emit gate + accelerated + declined + arg-evaluated-once | `codegen/jvm/JvmLinalgBlasAccelCompilerTest` (both packages) |
| the flag is value-less (the `--simd` dead-flag lesson) | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

The dead-flag guard matters more here than anywhere: every numeric assertion in these files
would pass on the scalar defun. `#'linalg:dot` printing `#<function LINALG:DOT>` and
`#'vec:matvec` printing `#<function VEC:MATVEC>` (interpreter), and the bridge name plus
the `blasMatvec` / `blasMatvecInto` METHODREF appearing in the class bytes (JVM) are the
assertions that fail when the flag is dead. The bridge's own bytes are base64 string
constants, so its method names are invisible there -- a methodref in the generated class's
constant pool is the interception itself.

Native image needs three things: the `JvmBlasTemplate.class` resource entry (already in
`resource-config.json`, beside the `--simd` one), `--enable-native-access=ALL-UNNAMED`,
which the `native` profile already passes, and a `foreign.downcalls` entry per SHAPE in
`reachability-metadata.json` -- six of them, the gemm shape at both widths both critical
and plain, the gemv shape at both widths critical. Native Image builds a downcall stub
only for a registered signature and REFUSES the handle for any other, so one missing entry
sends the whole static block down its catch and the binary reports "the foreign function
API is unavailable" on a machine whose tuned library is right there. `LinalgBlasKernels`
records the shapes as it binds them -- `bind` takes the LOOKUP so a machine with no CBLAS
can bind them against a stub -- and `LinalgBlasDeclineTest` pins them against the file.
`--gpu` shipped exactly this bug on the CUDA side (`.kb/gpu.md`, "Native image"). The exec jar carries
`Enable-Native-Access: ALL-UNNAMED` in its manifest; a compiled `.class` does not, so running
one prints the JVM's restricted-method warning unless the user passes the flag -- which the
docs say.
