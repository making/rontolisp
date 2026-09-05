# `--blas`: the matrix product on a tuned CBLAS

Second acceleration flag over the `linalg:` interception seam, extended to the `vec:` GEMV pair. It reuses `.kb/linalg-simd.md`'s declined-input protocol verbatim and only adds an attempt ahead of it; `.kb/linalg.md` has the accelerated library's semantics. **A tuned BLAS is RECOMMENDED, never required**: nothing bundled, nothing downloaded, and a machine without one runs the same programs to the same output.

| backend | interceptor | binding |
|---|---|---|
| interpreter (`prog.lisp --blas`, native binary included) | `eval/LinalgBlas` (`install` for `linalg:`, `installVec` for `vec:`) | `eval/LinalgBlasKernels` (java.lang.foreign) |
| JVM (`-o Prog.class --blas`) | `codegen/jvm/JvmLinalgKernelCompiler` (`linalg:` site), `JvmSimdCompiler.compileMatvecChain` (`vec:`) | `JvmBlasTemplate` (embedded bridge) |

WASM has no FFM, so `--blas` with a `.wasm` output is a hard error, not a silent no-op (`RontoLispCli.compileRecorded`). `--gpu` (`.kb/gpu.md`) reaches the same two backends and goes AHEAD of this one. Keep `doc/{en,ja}/guides/blas-acceleration.md` in sync on the intercepted set, install recommendation, thread note and precision contract.

## The chain

```
--blas --simd   ->  library gemm -> lane kernel -> scalar linalg.lisp defun
--blas          ->  library gemm ->               scalar linalg.lisp defun
--simd          ->                 lane kernel -> scalar linalg.lisp defun
```

- Every link uses the same partial-kernel protocol: null sentinel for a declined input, the layer below answers.
- Interpreter = install ORDER: `LinalgBlas.install` LAST; `LinalgBlas.installVec` after `VecSimd.install`, before `LinalgGpu.installVec`.
- JVM = `JvmLinalgKernelCompiler.compile` (was `JvmLinalgSimdCompiler`): each argument form is evaluated ONCE into a temp, then one `IFNONNULL` per attempt to a common end label — recompiling a form per rung would repeat side effects.
- The two flags embed SEPARATE bridge classes: `--blas` must not pull in the incubator Vector API, which would make the emitted class need `java --add-modules jdk.incubator.vector` (`theTwoFlagsAreOrthogonalAndEmbedTheirOwnBridges`).
- Emit gate: `JvmLispCompiler` scans for `JvmLinalgBlas.qualifiedMembers()` — `linalg:dot`, `vec:matvec`, `vec:matvec-into`. **A gate on `linalg:dot` alone embeds no bridge for exactly the programs the flag is for.**
- `linalg:` kernels are PARTIAL (null sentinel, call site falls through); `vec:` kernels are TOTAL (they SIGNAL), so `JvmSimdCompiler`'s bare `INVOKESTATIC` has no guard for a library attempt — hence `compileMatvecChain` (device -> library -> lane kernel -> spliced defun, one set of temps, bottom rung total). A `--simd`-only build keeps the bare `INVOKESTATIC` byte for byte.

## The intercepted set (three members, no more)

- **`linalg:dot`** in its three matrix shapes: matrix x matrix (`cblas_dgemm`/`cblas_sgemm`), matrix x vector and vector x matrix (`cblas_dgemv`/`cblas_sgemv`, the second with `CblasTrans`). `linalg:matmul` at rank <= 2 and `linalg:solve` accelerate TRANSITIVELY.
- **`vec:matvec`** / **`vec:matvec-into`**: one `cblas_?gemv`, `alpha = 1`, `beta = 0`, `CblasNoTrans` — the half that reaches the shipped numeric examples (`simd-dot`, `simd-gemv`, `tiny-llm`, `llama2`).
- Declined, memory-bound: `linalg:sum`, vector-vector `linalg:dot` / `vec:dot`, `axpy`, every element-wise `vec:` kernel, `vec:mean` / `vec:norm`.
- **`worth(n, m, p)` = `n*m*p >= 64`** (a critical downcall floors at ~30 ns).
- The stacked rank-3 product (`linalg::%la-matmul-nd`) is a SEPARATE interception, taken by `--simd` and `--gpu` but not here — see "Unfinished".
- `Linker.Option.critical(true)` takes heap `MemorySegment`s, so `MemorySegment.ofArray(a).asSlice(off * 8)` costs no copy but reaches no safepoint. **`2*n*m*p <= 2^32` goes critical, above that operands stage in a confined arena**; a gemv is always critical.

## Finding a TUNED library

**Rule: marker symbols.** "Found a CBLAS" is not the predicate: netlib's REFERENCE implementation exports the same symbols and is ~1.6x SLOWER than `--simd`, and Debian's `libblas.so.3` is an `update-alternatives` symlink. Accepted only if Accelerate (by framework path) or exporting a symbol the reference lacks: `openblas_get_config`, `mkl_get_version` / `MKL_Get_Version`, `bli_info_get_version_str`, `ATL_buildinfo`, `nvpl_blas_get_version`, `armpl_get_version`. `RONTOLISP_BLAS` overrides both search and check; `RONTOLISP_BLAS_VERBOSE=1` prints what was bound.

The candidate list, marker list, thread-query table, `MIN_WORK`, `CRITICAL_FLOP_CEILING`, `BARRIER_WORK` and `BARRIER_CALLS` are MIRRORED in `eval/LinalgBlasKernels` and `codegen/jvm/JvmBlasTemplate` (the template's bytes stand alone once embedded, so it cannot call the eval class). **Change them together.**

## Contract 1: precision

An accelerated product is CLOSE to the scalar defun, not equal, at both widths — a vendor BLAS reorders its reduction, so **which library and which VERSION is installed becomes part of the numerical answer** at `linalg`'s default width. Inputs exact at the operand width match exactly; inexact ones are pinned at < 1e-12 relative over a 64-long fold. The scalar `linalg.lisp` / `vec.lisp` defun stays the cross-backend oracle and **`--blas` stays out of `ci-spec.yaml`**. Pinned example outputs must NOT be loosened — the classifier-head shape differs by up to 5.5e-3 relative, enough to move an argmax.

## Contract 2: threads

A tuned BLAS is multi-threaded and rontolisp is not: one `linalg:matmul` may occupy every core. The docs name `OPENBLAS_NUM_THREADS` / `MKL_NUM_THREADS` / `VECLIB_MAXIMUM_THREADS`; rontolisp never sets them, and **`--blas-threads=N` was declined** because that variable already works on every backend the flag reaches.

- **For GEMV this is correctness-of-the-claim**: a decode loop's thousands of short calls can lose several times what the library buys. OpenBLAS's threaded/capped crossover is 0.4-4 Mflop, the SAME for gemv and gemm — what loses is a SHORT call, not a particular kernel.
- **Trap: a back-to-back probe flatters the threaded column.** OpenBLAS's pool keeps spinning between adjacent calls, so a microbenchmark never pays the wake-up a real program does. Trust end-to-end tok/s. Same mechanism as `.kb/simd-parallel.md` "Back-to-back calls are not the workload"; general rule in `.kb/measurement-probes.md`.
- **The barrier note is earned by the CALLS, not printed at flag time**: products of at most `BARRIER_WORK` = 2^21 flops are counted, and the `BARRIER_CALLS` = 64th writes ONE stderr line naming the library's variable. The count comes from an OPTIONAL symbol (`openblas_get_num_threads`, `mkl_get_max_threads` / `MKL_Get_Max_Threads`), so libraries exporting none (Accelerate, BLIS) and already-capped ones stay silent. Mirrored in `eval/LinalgBlasKernels.barrierNote` and `JvmBlasTemplate.note`.
- **Accelerate inverts this**: no thread symbols, threads only big shapes by an OPERAND BYTES rule rather than a flop count (**do not carry OpenBLAS's crossover across**), no wake-up cost, and capping COSTS ~9%. BLIS stays out of `THREAD_QUERIES` until a machine with BLIS is measured.
- Probes for a QUIET machine (`RONTOLISP_BLAS` / `PROBE_BLAS` names the library): `.todo/123-gpu-acceleration/{AccelerateProbe,GemvProbe,ThreadBarrierProbe}.java`; `GemvProbe` times against the bridge's LANE kernel (`FloatVector.SPECIES_128` for `#f`, `DoubleVector.SPECIES_PREFERRED` for `#d`).

## Native image

Three things: the `JvmBlasTemplate.class` entry in `resource-config.json`; `--enable-native-access=ALL-UNNAMED` (the `native` profile and the exec jar's manifest pass it, a compiled `.class` warns without it); and a `foreign.downcalls` entry per SHAPE in `reachability-metadata.json` — six: the gemm shape at both widths both critical and plain, the gemv shape at both widths critical. **An unregistered signature gets no downcall stub, so one missing entry sends the whole static block down its catch and the binary reports "the foreign function API is unavailable" on a machine whose tuned library is right there.** `LinalgBlasKernels.bind` takes the LOOKUP so a machine with no CBLAS binds against a stub, and records the shapes as it binds.

## Unfinished: growing the member set past `dot`

A stacked product IS a batch of gemms and `gemm`/`gemmF` already take element OFFSETS, but:
- `JvmLinalgBlas.handles` compares one name, `JvmBlasRuntimeBuilder` registers one `ops` key (`DOT`), `JvmLinalgKernelCompiler.compile` hardcodes it: a second member needs a member->key map on all three plus the emit-gate scan.
- `JvmBlasTemplate` must stand alone once embedded, so it needs its own `laDims` / `laBcastShape` / `laBatchStrides` / odometer (~120 lines from `JvmSimdVectorTemplate`). Grow it on `--gpu`'s blob closure renamed by one prefix rule (`.kb/gpu.md`, "The JVM backend").
- `worth(n, m, p)` must be re-decided per batch as PER-MATRIX work (`batches` downcalls); `--gpu`'s total-work answer does NOT carry over, since a device runs the whole stack in ONE launch. Precision grows a case too: a library gemm per batch is only "close to" `--simd`'s exact per-batch `linalg:dot`.

## Tests

- `eval/LinalgBlasTest` (both packages) — interpreter, needs a library (`@EnabledIf`).
- `eval/LinalgBlasDeclineTest` (both packages) — interpreter, must hold on EVERY machine; `theThreadBarrierNoteIsEarnedByTheProgramShapeAndNotByTheFlag` plus the `foreign.downcalls` shape pinning.
- `codegen/jvm/JvmLinalgBlasAccelCompilerTest` (both packages) — JVM emit gate, accelerated, declined, arg-evaluated-once.
- `cli/CliOptionsTest`, `cli/RontoLispCliTest` — the flag is value-less (the `--simd` dead-flag lesson).

**Dead-flag guard: every numeric assertion in these files would pass on the scalar defun.** What fails when the flag is dead is `#'linalg:dot` / `#'vec:matvec` printing `#<function LINALG:DOT>` / `#<function VEC:MATVEC>`, and the `blasMatvec` / `blasMatvecInto` METHODREF appearing in the class bytes — the bridge's own bytes are base64 constants, so a methodref in the generated constant pool IS the interception.
