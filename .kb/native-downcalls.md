# `src/native/java`: the binary's downcalls through SubstrateVM's own AOT route

**Invariant.** Inside the native image an FFM downcall handle is INTERPRETED -- SubstrateVM's
`Target_java_lang_invoke_LambdaForm.forceInterpretation()` returns `true`, so a handle that did not
exist at build time is walked Name by Name with every argument boxed, ~1.7 us a call plus ~0.4 us
per argument (`.kb/gpu.md`, "An FFM downcall inside a native image costs"; `.todo/727`). The same
address invoked through an `@InvokeCFunctionPointer` interface method, compiled at build time and
handed the pointer at run time, costs what the JVM's call costs. **A hot downcall the binary issues
is re-issued through that route by a `@TargetClass` substitution in `src/native/java`, compiled
only under `-Pnative`; the FFM binding stays what `java -jar` and every compiled `.class` run.**

## The seam

- **`pom.xml`, `native` profile**: `build-helper-maven-plugin` adds `src/native/java` (the way
  `-Pweb` adds `src/web/java`), and `org.graalvm.sdk:nativeimage` is a `provided` dependency
  version-locked to the GraalVM the image is built with (25.0.4) -- it carries
  `com.oracle.svm.core.annotate` (`@TargetClass`, `@Substitute`, `@Alias`),
  `org.graalvm.nativeimage` (`PinnedObject`, `c.function`, `c.type`) and `org.graalvm.word`. The
  artifact and not the JDK module, so `--release 25` stays: the JDK module is invisible under
  `--release` (ct.sym), which is why `-Pweb` blanks `maven.compiler.release` and this profile does
  not have to. In the published `word` artifact `Word` is package-private; use `WordFactory.pointer`.
- **A substitution takes the ADDRESS the FFM lookup already found**: the main class records
  `symbol.address()` beside each handle (`LinalgBlasKernels.DGEMM_ADDRESS` ... four `long`s, 0
  when unbound) and the `Target_` class reads them by `@Alias`. The candidate search, the tuned-marker
  check and the thread query are not duplicated, and the handles are STILL MADE in the image --
  which is why every shape stays in `reachability-metadata.json` and the registration tests
  (`LinalgBlasDeclineTest`, `NativeImageForeignConfigTest`) do not change: an unregistered shape
  refuses the handle and the whole static block goes down its catch before any substitution runs.
- **One `@InvokeCFunctionPointer` interface method per SHAPE**, a heap array per `PinnedObject`
  (`addressOfArrayElement(offset)` takes the element offset the callers pass) and the call
  transitions to native, so a long product is safepoint-friendly and the staged-arena branch the
  JVM takes above `CRITICAL_FLOP_CEILING` has no counterpart here.
- **Two image-build traps, both fatal and both silent at `javac`**: a Word-typed value in a
  `static final` field fails `StaticFinalFieldFoldingPhase` with "missing StateSplitProxy", and a
  Word-typed value crossing a lambda fails with "Expected Object but got Word". Make the pointer
  inside the method, from a `long`.
- **`./mvnw test` compiles none of this.** `NativeSubstitutionsTest` reads each `Target_*.java`
  and checks every `@Alias` field, `@Alias` method and `@Substitute` method against the target
  class by reflection -- the NAMES and parameter types, which is what a rename in the main tree
  breaks; the bodies are verified on the built binary (`.kb/session-workflow.md`, "After task completion").

## What took the route: `--blas` (2026-09-10, `.todo/729`)

`Target_LinalgBlasKernels` substitutes `gemm`, `gemmF`, `gemv`, `gemvF`. Measured on GB10
(aarch64, GraalVM 25.0.4, OpenBLAS at one thread), probes and outputs in
`.todo/artefacts/729-the-native-binarys-downcalls-through-svms-own-aot-route/`:

| per-call floor, ns (callee returns at once) | FFM handle in the image | `@InvokeCFunctionPointer` | JVM |
| --- | --- | --- | --- |
| `cuCtxSetCurrent` (1 argument) | 2100-2117 | 11.4 | 9.4 |
| `cuMemcpyHtoD_v2` (3) | 2944-2952 | 16.4 | 15.4 |
| `cuLaunchKernel` (11) | 6168-6175 | 16.1 | 17.1 |
| `cblas_dgemv` (12, three pins) | 6445-6462 | 88.5-89.0 (82.5 without the transition) | 7.7 critical |
| `cblas_dgemm` (14, three pins) | 7257-7269 | 91.5 | 6.9 critical |

A real 64x64 `dgemv`: 7.0 us through the handle, 0.5 through the pointer. The three
`PinnedObject`s are ~25 ns each and are the whole gap to the JVM's critical call.

`vec:matvec` and `linalg:dot` in the binary, us per call, `--simd` lane kernel against
`--blas --simd` (`crossover.lisp`, `WHAT=mv` / `WHAT=dot`), the "before" row on the binary built
at `9f5019125` the same morning:

| `vec:matvec` n x n by n | 8 | 16 | 32 | 64 | 128 | 192 | 256 | 384 | 512 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| lane kernel | 0.70 | 0.73 | 0.94 | 1.72 | 4.92 | 10.1 | 17.4 | 39.1 | 68.2 |
| library, before (declined below 2^17) | 0.79 | 0.76 | 0.96 | 1.82 | 5.08 | 10.5 | 18.0 | 27.3 | 40.7 |
| library, after | 0.83 | 0.77 | 0.81 | 1.13 | 2.87 | 5.35 | 8.85 | 17.0 | 31.5 |

| `linalg:dot` n x n by n x n | 2 | 4 | 8 | 16 | 24 | 32 | 64 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| lane kernel | 0.70 | 0.65 | 0.95 | 2.97 | 8.43 | 18.8 | 142 |
| library, after | 0.72 | 0.73 | 0.78 | 0.96 | 1.52 | 2.29 | 10.5 |

Under 20% behind at the smallest shapes the threshold admits (8x8 gemv, 4x4x4 gemm -- the
interpreter's own ~0.6 us per call is most of both columns there), level at 16x16 / 8x8x8, 1.5x
ahead at 64x64, 3x at 16x16x16, 8x at 32x32x32 -- the JVM's crossover (library 0.33-0.67 us
against a 0.5-1.4 us lane kernel from 16x16 up; before, the binary was level at 24x24x24 and 2x
BEHIND at 128x128). So `LinalgBlasKernels.MIN_WORK` is ONE number (64) again; the native pair
2^15 / 2^17 that existed from 2026-09-07 is gone, and
`LinalgBlasDeclineTest.theWorkThresholdIsOneNumberOnEveryRuntime` pins it. `check.lisp` (the
artefacts) runs the four products at both widths and sizes 3-300 with exact-integer operands:
plain, `--simd`, `--blas`, `--blas --simd` print identical bytes on the binary.

## What did not: `--gpu` and objc, measured and refused

The CUDA route is real at the call (11-16 ns above) and was NOT widened, on these numbers:

- A `--gpu` member with its result read back is 63 us in the binary against 30 on the JVM, of
  which the 5.65 driver calls' interpretation is ~19 us (`.todo/727`, section 6) -- what the route
  would recover -- and the other ~30 us is the AOT interpreter's own Lisp. The device thresholds
  hold in the binary as they are (`Gpu.POOLED_MIN_WORK` sits at 2.4x the lane kernel there).
- On the model the device arm is for (Qwen3.5-0.8B, BF16 GGUF, `examples/llm/llm.lisp -w bf16`),
  the binary's forward is the INTERPRETER's: **0.12 tok/s under `--gpu --simd`** (`-m chat -t 0
  -n 48`, 8.3 s a forward; the chat prompt alone 156 s, and 510 s under `--simd`, after the 45 s
  load) where the JVM class output is 25 ms. A decode forward's ~1300 driver calls are ~5 ms of
  interpretation -- 0.06% of that forward. The .kb rule that "compiling the program to a class is the way around that cost"
  (`doc/en/guides/gpu-acceleration.md`) is the answer, not a faster driver call.
- The cost side is ~30 entry points in `CudaDriver` (~20 distinct shapes) plus their address
  plumbing in `am.ik.gpu` (a nested record there joins `JvmGpuRuntimeBuilder.GPU_CLASSES`),
  verified only by hand on the device, in a profile no test compiles. `MetalDriver` and the objc
  `objc_msgSend` table (a generic per-selector shape set, `.kb/objc.md`) are macOS-only and
  unmeasurable on the box that has the seam; nothing there is calibrated against the JVM's send
  cost, so no threshold is wrong.

**Revisit when the binary's `--gpu` host side is within ~2x of the JVM's per member** -- which
means the interpreter's own overhead, not the driver's, has moved. The upstream ask that would make
the substitution unnecessary is an AOT invoker for a REGISTERED downcall shape, asked upstream on
[oracle/graal#12219](https://github.com/oracle/graal/issues/12219) (GR-75754) -- the text is
`.todo/730`'s `comment-on-12219.md`.

## Tests

- `NativeSubstitutionsTest` -- every `src/native/java` member against its target class, JVM lane.
- `LinalgBlasDeclineTest` -- the one threshold, the registered shapes.
- On the built binary, by hand: `check.lisp` (bytes identical across the four flag sets) and
  `crossover.lisp` (`.todo/artefacts/727-.../`, `WHAT=mv` and `WHAT=dot`).
