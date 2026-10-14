# `src/native/java`: the binary's downcalls through SubstrateVM's own AOT route

**Invariant.** Inside the native image an FFM downcall handle is INTERPRETED -- SubstrateVM's
`Target_java_lang_invoke_LambdaForm.forceInterpretation()` returns `true`, so a handle that did not
exist at build time is walked Name by Name with every argument boxed
(`.kb/gpu.md`, "An FFM downcall inside a native image costs"; `.todo/727`). The same
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

`Target_LinalgBlasKernels` substitutes `gemm`, `gemmF`, `gemv`, `gemvF`. The route was taken
because through the FFM handle every call is interpreted while through
`@InvokeCFunctionPointer` it costs what the JVM's does, and at the shapes the work threshold
admits the library route is level or ahead of the `--simd` lane kernel
(`crossover.lisp`, `WHAT=mv` / `WHAT=dot`; probes in
`.todo/artefacts/729-the-native-binarys-downcalls-through-svms-own-aot-route/`). The three
`PinnedObject`s are the whole gap to the JVM's critical call. So
`LinalgBlasKernels.MIN_WORK` is ONE number (64) on every runtime; the native pair
2^15 / 2^17 that existed from 2026-09-07 is gone, and
`LinalgBlasDeclineTest.theWorkThresholdIsOneNumberOnEveryRuntime` pins it. `check.lisp` (the
artefacts) runs the four products at both widths and sizes 3-300 with exact-integer operands:
plain, `--simd`, `--blas`, `--blas --simd` print identical bytes on the binary.

## What did not: `--gpu` and objc, refused

The CUDA route is real at the call and was NOT widened:

- A `--gpu` member's cost in the binary is mostly the AOT interpreter's own Lisp, not the driver
  calls' interpretation (`.todo/727`, section 6) -- the latter is what the route would recover.
  The device thresholds hold in the binary as they are (`Gpu.POOLED_MIN_WORK` sits where it does
  against the lane kernel there).
- On the model the device arm is for (Qwen3.5-0.8B, BF16 GGUF, `examples/llm/llm.lisp -w bf16`),
  the binary's forward is the INTERPRETER's under `--gpu --simd`, where the JVM class output
  compiles the program to a class. A decode forward's many driver calls are a negligible share of
  it. The .kb rule that "compiling the program to a class is the way around that cost"
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
