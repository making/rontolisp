# `--blas`: the matrix product on a tuned CBLAS

Second acceleration flag over the `linalg:` interception seam, extended to the `vec:` GEMV pair. Read `.kb/linalg-simd.md` first — this reuses its declined-input protocol verbatim and only adds an attempt ahead of it; `.kb/linalg.md` has the semantics of the library being accelerated.

| backend | interceptor | binding |
|---|---|---|
| interpreter (`prog.lisp --blas`, native binary included) | `eval/LinalgBlas` (re-`defineFunction`: `install` for `linalg:`, `installVec` for `vec:`) | `eval/LinalgBlasKernels` (java.lang.foreign) |
| JVM (`-o Prog.class --blas`) | `codegen/jvm/JvmLinalgKernelCompiler` (`linalg:` call site) and `JvmSimdCompiler.compileMatvecChain` (`vec:`) | `JvmBlasTemplate` (embedded bridge) |

WASM has no FFM, so `--blas` with a `.wasm` output is a hard error, not a silent no-op (`RontoLispCli.compileRecorded`); the WASM backends keep `--simd`. `--gpu` (`.kb/gpu.md`) reaches the same two backends and goes AHEAD of this one. User-facing page: `doc/{en,ja}/guides/blas-acceleration.md` — keep the intercepted set, install recommendation, thread note and precision contract in sync with it.

## Posture
**A tuned BLAS is RECOMMENDED, never required.** Nothing bundled, nothing downloaded; a machine without one runs the same programs to the same output. ONE mechanism — find a tuned CBLAS, verify it is tuned, use it, otherwise decline to the kernel we already have. Platforms differ only in what the search finds (macOS finds Accelerate for free; a Linux user is told to install OpenBLAS).

## Why its own flag, not part of `--simd`
`--simd`'s three backends agree bit for bit with each other and, at `#d`, with the scalar defun, because we wrote all three kernels. A vendor BLAS blocks and reorders its reduction, so **which library and which VERSION is installed becomes part of the numerical answer** at `linalg`'s DEFAULT width. Composition is a chain:

```
--blas --simd   ->  library gemm -> lane kernel -> scalar linalg.lisp defun
--blas          ->  library gemm ->               scalar linalg.lisp defun
--simd          ->                 lane kernel -> scalar linalg.lisp defun
```

Each link uses the SAME partial-kernel protocol: return the null sentinel for a declined input, the layer below answers. Interpreter = install order (`LinalgBlas.install` runs LAST, capturing whatever `linalg:dot` was bound to). JVM = `JvmLinalgKernelCompiler.compile`, which evaluates each argument form ONCE into a temp and emits one `IFNONNULL` per attempt to a common end label — the temps are what make a chain of any length safe, since recompiling argument forms would repeat side effects.

`JvmLinalgSimdCompiler` was renamed `JvmLinalgKernelCompiler`: it is the one `linalg:` call-site compiler and emits a chain of up to two bridges. The two bridges are separate embedded classes on purpose — `--blas` must not drag in the incubator Vector API, which would make the emitted class need `java --add-modules jdk.incubator.vector` (pinned by `theTwoFlagsAreOrthogonalAndEmbedTheirOwnBridges`).

## The intercepted set (three members, no more)
- **`linalg:dot`** in its three matrix shapes: matrix x matrix (`cblas_dgemm`/`cblas_sgemm`), matrix x vector and vector x matrix (`cblas_dgemv`/`cblas_sgemv`, the second with `CblasTrans`). `linalg:matmul` at rank <= 2 and `linalg:solve` are accelerated TRANSITIVELY through their `linalg.lisp` bodies.
- **`vec:matvec`** and **`vec:matvec-into`**: one `cblas_?gemv` with `alpha = 1`, `beta = 0`, `CblasNoTrans`. The `-into` form is the better fit — gemv writes into a caller-supplied `y`, so the interception drops the result allocation as well as the loop. The `vec:` half is what makes the flag reach the shipped numeric examples (`simd-dot`, `simd-gemv`, `tiny-llm`, `llama2` are all `vec:` programs; an LLM decode is GEMV end to end).
- `vec:mean` / `vec:norm` are not intercepted — they fold, they do not multiply.

Declines, by measurement:
- **Memory-bound members gain nothing**: `linalg:sum`, vector-vector `linalg:dot` / `vec:dot`, `axpy`, every element-wise `vec:` kernel. A library call cannot beat a lane loop over the same bytes, and `--simd` already covers them. This is what keeps `vec:dot` out while `vec:matvec` beside it is in.
- **The stacked rank-3 product (`linalg::%la-matmul-nd`) is a separate interception**, taken by `--simd` and `--gpu` but not here — see below.
- **`worth(n, m, p)` = `n*m*p >= 64`.** A critical downcall floors at ~30 ns and the crossover against a JIT-warm `ikj` triple loop is about 4x4x4.

## `vec:` gets its own guarded call site, not a rung inside the `--simd` bridge
The `linalg:` kernels are PARTIAL (null sentinel, call site falls through); the `vec:` kernels are TOTAL (they accept packed float arrays of one width and SIGNAL otherwise), so `JvmSimdCompiler` emits a bare `INVOKESTATIC` and `VecSimd` installs a signalling native — no guard to hang a library attempt on.
- Rejected: putting the attempt inside the lane bridge (`simdMatvec` tries the library, then its own loop). It would make the `vec:` half of `--blas` require `--simd` (a `--blas`-only build would run the scalar `vec.lisp` defun for a GEMV, undiscoverably) and drag FFM into the `--simd` bridge and the Vector API into the `--blas` one, destroying the orthogonality `JvmBlasRuntimeBuilder` exists for.
- Done: `vec:matvec` / `vec:matvec-into` get a guarded CHAIN of their own — device -> library -> lane kernel -> spliced defun, over one set of temps, each rung declining with `null`, bottom rung total. `--gpu` had already built this for `vec:matvec` (`JvmSimdCompiler.compileGpuMatvec`), so the work was generalizing that site into `compileMatvecChain` (gpu/blas/simd rungs each optional, defun always) and routing both members through it. `JvmExprCompiler` claims the site when `--blas` emitted its bridge (either member) or `--gpu` emitted its own (allocating form only); a `--simd`-only build keeps the bare `INVOKESTATIC` byte for byte. Interpreter install order is the same: `LinalgBlas.installVec` after `VecSimd.install`, before `LinalgGpu.installVec`.
- Emit gate: `JvmLispCompiler` scans for `JvmLinalgBlas.qualifiedMembers()` — `linalg:dot`, `vec:matvec`, `vec:matvec-into`. A gate on `linalg:dot` alone would embed no bridge for exactly the programs the flag is for.

## Why `--blas` stopped at `dot` (what a later item needs)
A stacked product IS a batch of gemms (a `cblas_?gemm` per matrix) and the `gemm`/`gemmF` entry points already take element OFFSETS, but:
- Not "a handful of lines": `JvmLinalgBlas.handles` compares one name, `JvmBlasRuntimeBuilder` registers one `ops` key (`DOT`), and `JvmLinalgKernelCompiler.compile` hardcodes that key. A second member needs a member->key map on all three plus the emit-gate scan.
- The template cannot borrow the batch walk: `JvmBlasTemplate`'s bytes must stand alone once embedded (no reference to another rontolisp class), so it would need its own copy of `laDims` / `laBcastShape` / `laBatchStrides` / the odometer (~120 lines duplicated from `JvmSimdVectorTemplate`). The way out exists — `--gpu`'s blob carries a CLOSURE of classes renamed by one prefix rule (`.kb/gpu.md`, "The JVM backend") — and is the mechanism to grow this member set on.
- `worth(n, m, p)` must be re-decided per batch: for a batch the right predicate is PER-MATRIX work (`batches` downcalls), not total work. `--gpu`'s answer does NOT carry over — a device runs the whole stack in ONE launch, so its threshold is TOTAL work.
- The precision contract grows a case: `--simd`'s batched kernel is a per-batch `linalg:dot` exactly; a library gemm per batch is only "close to".

## Identifying a TUNED library is the load-bearing part
"Found a CBLAS" is not the predicate: the netlib REFERENCE implementation exports the same symbols and is SLOWER than our own `--simd` matmul (~1.6x regression at linalg's default width). The soname proves nothing either — Debian's `libblas.so.3` is an `update-alternatives` symlink.

**Rule: marker symbols.** A candidate is accepted only if it is Accelerate (by framework path) or exports a symbol a tuned implementation has and the reference does not: `openblas_get_config`, `mkl_get_version` / `MKL_Get_Version`, `bli_info_get_version_str`, `ATL_buildinfo`, `nvpl_blas_get_version`, `armpl_get_version`. Cheap, deterministic, no startup benchmark. `RONTOLISP_BLAS` overrides both search and check; `RONTOLISP_BLAS_VERBOSE=1` prints what was bound.

The candidate list, marker list, thread-query table, `MIN_WORK`, `CRITICAL_FLOP_CEILING`, `BARRIER_WORK` and `BARRIER_CALLS` are MIRRORED in `eval/LinalgBlasKernels` and `codegen/jvm/JvmBlasTemplate` (the template's bytes stand alone once embedded, so it cannot call the eval class). **Change them together.**

## No copy: `Linker.Option.critical` takes heap segments
A `critical(true)` downcall accepts heap `MemorySegment`s, and `MemorySegment.ofArray(a).asSlice(off * 8)` carries the compiled representation's dimension header out of the picture for free — so there is no heap->native copy cost. The price: a critical call does NOT transition the thread to native, so the VM cannot reach a safepoint while it runs.

**Hybrid: `2*n*m*p <= 2^32` goes critical; above that the operands are staged in a confined arena.** 2^32 flops is ~5 ms on a tuned library, and from there up the staging copy is a few percent of the call, so bounded GC latency is nearly free exactly where it matters. A gemv is always critical (memory-bound, duration bounded by the operand it was handed).

## The two contracts
### 1. Precision
An accelerated product is CLOSE to the scalar defun, not equal, at both widths. Over inputs exact at the operand width (integers, powers of two) results match exactly — which is what lets cross-backend tests assert equality; over inexact ones they differ in the last few ulps (pinned at < 1e-12 relative over a 64-long fold rather than at equality, because the library and its version decide the figure). The scalar `linalg.lisp` / `vec.lisp` defun remains the cross-backend oracle, and **`--blas` stays out of `ci-spec.yaml`**.

The pinned example outputs were the risk and did not move: the `vec:` half reorders an `#f` reduction on exactly the shapes whose DERIVED integers the examples pin (max relative difference on the classifier-head shape is 5.5e-3, enough to move an argmax). Each was RUN: `simd-gemv` prints the same indices under `--blas`, `--simd --blas` and both thread settings; `tiny-llm` the same nine tokens; `llama2` the same story, byte-identical at 150 tokens on stories15M. Examples were NOT loosened. If a future machine or library version moves one, fix that — do not loosen the expectation.

### 2. Threads
A tuned BLAS is multi-threaded and rontolisp is not: one `linalg:matmul` may occupy every core. The docs name `OPENBLAS_NUM_THREADS` / `MKL_NUM_THREADS` / `VECLIB_MAXIMUM_THREADS` rather than fighting them; rontolisp never sets them (a library's pool is the user's to size).

**For the GEMV half this is correctness-of-the-claim, not courtesy.** A decode loop is thousands of short memory-bound calls and the per-call barrier swamps them: on a 64-core OpenBLAS machine `examples/llama2/llama2.lisp` on stories15M runs 123 tok/s with `OPENBLAS_NUM_THREADS=1` and **16 tok/s** at the 64-thread default (a 5.4x LOSS where the capped build is a 1.15x win), and the loss GROWS with machine contention. Since `--simd --parallel` (`.kb/simd-parallel.md`) threads the lane GEMM too, the library still wins it by 1.3-3.8x at n = 128..1024, so this flag stays the answer where a library exists.

**Trap: a back-to-back probe flatters the threaded column.** OpenBLAS's pthread pool keeps spinning while calls follow each other, so a microbenchmark never pays the wake-up a real program does — the same 288x288 `#f` gemv is 17.4 us hot and **90.0 us** with ~200 us of unrelated Java work between calls, against 13.3 us capped. Trust end-to-end tok/s, not a ratio table. The identical mechanism was found once before over a `ForkJoinPool` (`.kb/simd-parallel.md`, "Back-to-back calls are not the workload") and the finding did not travel; the general rule — a probe whose SHAPE differs from the step's shape measures something the program never runs — is `.kb/measurement-probes.md`.

**The barrier note is earned by the CALLS, not printed at flag time.** A flag-time warning would fire identically on the program it would ruin (`examples/ml/blas-matmul.lisp` wants all 64 threads). Instead: intercepted products of at most `BARRIER_WORK` = 2^21 flops are counted, and the `BARRIER_CALLS` = 64th one writes ONE line to stderr naming the library's variable. 64 is below `examples/ml/simd-gemv.lisp`'s 100 GEMVs — the smallest shipped program that measurably loses (371 ms threaded vs 131 ms capped) — and high enough that a handful of products is not a loop. The thread count comes from an OPTIONAL symbol (`openblas_get_num_threads`, `mkl_get_max_threads` / `MKL_Get_Max_Threads`, an `int()` downcall the metadata already registered), so a library exporting none (Accelerate, BLIS) and a library already capped keep today's exact silence. Mirrored in `eval/LinalgBlasKernels.barrierNote` (pure, given the running count rather than reading it, so the policy is testable on a machine with no CBLAS) and `codegen/jvm/JvmBlasTemplate.note`.

On OpenBLAS the threaded/capped crossover is between 0.4 and 4 Mflop and **is the same for gemv and gemm**: what loses to a threaded library is a SHORT call, not a particular kernel. Below it threads cost up to 6.8x; above it they buy up to 6.2x.

**`--blas-threads=N` was considered and declined.** `openblas_set_num_threads(1)` in process does recover the win, but the remedy already exists on every backend the flag reaches (the library's own variable, which a compiled `.class` under `java Prog` reads exactly as the interpreter does); the flag's own mechanism measured slightly worse (the pool is created then abandoned); and the compile path would need a numeric constant injected into a template whose bytes are embedded verbatim, a mechanism that does not exist. Build it only if a library appears whose thread count CANNOT be set from the environment.

**Accelerate is different in both directions, measured.** It exports NONE of the seven thread symbols probed (`openblas_get_num_threads`, `openblas_set_num_threads`, `openblas_get_parallel`, `mkl_get_max_threads`, `MKL_Get_Max_Threads`, `mkl_set_num_threads`, `bli_thread_get_num_threads`), so the note is silent on Apple — now for a measured reason, not a structural one.
- It is single-threaded at every shape a decode loop makes (capping changes nothing from 131 Kflop to 33.6 Mflop, gemv and gemm alike) and threads only the big ones. The switch is NOT a flop count: a 33.6 Mflop `sgemm 256^3` stays serial while an 18.4 Mflop `sgemv 32000x288` threads — an OPERAND BYTES rule (36.9 MB vs 256 KB that fits in cache). **Do not carry OpenBLAS's 0.4-4 Mflop crossover across.**
- Where it does thread it pays NO wake-up: `dgemv 2048x2048` is the same time hot and with a 200 us gap, where OpenBLAS goes 17.4 -> 90.0 us. The trap is absent.
- Small shapes' gap cost is a cold cache, not a barrier — the separating test is "does the cap remove the gap's cost", which is cheaper than reading a thread count.
- End to end (llama2 stories15M, JVM class output, 150 greedy tokens): `--simd` ~540 tok/s, `--simd --blas` **~1025**, `--simd --blas` with `VECLIB_MAXIMUM_THREADS=1` ~930. A 1.90x win uncapped, and capping COSTS 9% — the opposite of OpenBLAS in both directions, which is why the note is earned by calls rather than printed at flag time. **`--blas` needs no change on Apple.** If a future macOS threads the short calls, the observable is the cap moving a 288x288 gemv.
- **BLIS stays out of `THREAD_QUERIES`**: no machine measured so far has one, and an unmeasured entry is what that decision existed to avoid. It needs a machine with BLIS, not a decision.

Probes, kept runnable anywhere: `.todo/123-gpu-acceleration/AccelerateProbe.java` (library vs `--simd`), `GemvProbe.java` (back to back, times against the LANE kernel the bridge actually runs — `FloatVector.SPECIES_128` for `#f`, `DoubleVector.SPECIES_PREFERRED` for `#d`, both folding with `mul().add()`), `ThreadBarrierProbe.java` (one call, unrelated work in between). All need a QUIET machine. `RONTOLISP_BLAS` / `PROBE_BLAS` names the library. Re-run the library and `--simd` halves together, never one against a stale copy of the other.

Note on the lane kernel's pinned `SPECIES_128`: 128-bit lanes are all NEON has, while an AVX2 machine gives the JIT 256-bit registers for the same source, so x86-64's single-thread ratio over `--simd` is about a third of Apple's. The premise (gemv is worth binding) holds on both; the 6-9x figure is Apple's, not the feature's.

`--blas` WITHOUT `--simd` is also much of a wash on the interpreter: the GEMV becomes a library call but `vec:dot` and `vec:scale` beside it stay interpreted defuns, which is most of what is left. Where `--blas` is a wash: a GEMV on the NATIVE BINARY at small shapes ties `--simd`, because the native binary's lane kernel is already at library speed. The flag is worth passing for a GEMV when the matrix is large, the library is capped to one thread, and the rest of the loop is compiled — two of those three failing makes it a wash, all three a loss.

## Tests
| what | where |
|---|---|
| interpreter, needs a library on the machine (`@EnabledIf`) | `eval/LinalgBlasTest` (both packages) |
| interpreter, must hold on EVERY machine | `eval/LinalgBlasDeclineTest` (both packages) |
| thread-barrier note policy, every machine | `eval/LinalgBlasDeclineTest.theThreadBarrierNoteIsEarnedByTheProgramShapeAndNotByTheFlag` |
| JVM emit gate + accelerated + declined + arg-evaluated-once | `codegen/jvm/JvmLinalgBlasAccelCompilerTest` (both packages) |
| the flag is value-less (the `--simd` dead-flag lesson) | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

**The dead-flag guard matters more here than anywhere**: every numeric assertion in these files would pass on the scalar defun. The assertions that fail when the flag is dead are `#'linalg:dot` printing `#<function LINALG:DOT>` and `#'vec:matvec` printing `#<function VEC:MATVEC>` (interpreter), and the bridge name plus the `blasMatvec` / `blasMatvecInto` METHODREF appearing in the class bytes (JVM) — the bridge's own bytes are base64 string constants, so a methodref in the generated class's constant pool IS the interception.

## Native image
Three things: the `JvmBlasTemplate.class` resource entry (already in `resource-config.json`, beside the `--simd` one), `--enable-native-access=ALL-UNNAMED` (the `native` profile passes it), and a `foreign.downcalls` entry per SHAPE in `reachability-metadata.json` — six: the gemm shape at both widths both critical and plain, the gemv shape at both widths critical. Native Image builds a downcall stub only for a registered signature and REFUSES the handle for any other, so one missing entry sends the whole static block down its catch and the binary reports "the foreign function API is unavailable" on a machine whose tuned library is right there (`--gpu` shipped exactly this bug on the CUDA side). `LinalgBlasKernels` records the shapes as it binds them — `bind` takes the LOOKUP so a machine with no CBLAS can bind against a stub — and `LinalgBlasDeclineTest` pins them against the file.

The exec jar carries `Enable-Native-Access: ALL-UNNAMED` in its manifest; a compiled `.class` does not, so running one prints the JVM's restricted-method warning unless the user passes the flag (the docs say so).
