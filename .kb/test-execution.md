# Test execution: how the suite actually runs

## Sequencing

- `src/test/resources/junit-platform.properties` sets `parallel.mode.default` and
  `parallel.mode.classes.default` to `same_thread`. A class opts its OWN methods into
  concurrency only with class-level `@Execution(ExecutionMode.CONCURRENT)`: today
  `WasmLispCompilerIntegrationTest`, `RoveTestCommandE2eTest`, and every subclass of
  `AsdfLibraryE2eSupport`. Everything else -- including all of `am.ik.gpu` /
  `eval.LinalgGpuTest` -- runs one method at a time in one thread.
- **Trap: `[rontolisp] JUnit parallelism = N` at the start of a run is NOT evidence of
  parallel test execution.** It is `CoreCountParallelismStrategy` printing the value it
  derived for `junit.jupiter.execution.parallel.config.custom.class`, which governs
  intra-class parallelism only for a class that opted in.
- `pom.xml` surefire runs `forkCount=2`, `reuseForks=true`. A fork is a separate JVM PROCESS,
  so nothing in one process's heap (a weakly-keyed cache, a static counter,
  `am.ik.gpu.DeviceResidency`'s live set) is visible across forks; surefire guarantees neither
  which fork nor what order.

## Determinism a test assumes but the JVM does not owe it

- **A test asserting an exact `residentBytes()` must KEEP ITS ARRAYS REACHABLE**
  (`aCollectedHostArrayTakesItsResidentCopyWithIt`): `Reference.reachabilityFence` on every
  array the total counts, after the last assertion, and never pass an array anonymously
  (`Gpu.map(op, a, 0, new float[n], 0, n)`).
- **A PROCESS-WIDE counter's diff around one call is not that call's own effect.**
  `DeviceResidency.dirtyCount()`/`backingCount()` are live-set sizes over a shared weakly-keyed
  cache; `System.gc()` is a hint, not a guarantee. Use the PER-HANDLE predicate
  `DeviceResidency.dirty(Object)`/`.backed(Object)`, exposed as
  `GpuThresholds.isDirty(Object)`/`.isBacked(Object)`.

## A test that never ran the mechanism it asserts on

A test exercising a THRESHOLD-gated mechanism must build a shape clearing the threshold
**gating the mechanism under test**, on the machine it runs on. Otherwise nothing errors: the
gated path declines, the fallback computes the same answer, every assertion passes, and the
test pins nothing. Three spellings (all found in the `--gpu` suites; per-test detail in
`.kb/gpu.md`, "Tests"): a shape sized off threshold A while mechanism B is under test; a
`Long.MAX_VALUE` sentinel put through arithmetic (`2 * threshold` wraps NEGATIVE, and a
following `Math.max(floor, ...)` hands back the caller's own floor); a hard-coded dimension
predating a second backend.

- Best proof: a RUNTIME census -- a counter only the accepted path moves
  (`GpuThresholds.residencyHits()`/`.residencyMisses()`). Where the mechanism runs in another
  loader/process, assert the SHAPE against the threshold via
  `GpuThresholds.acceptedForSize(threshold, elements)`.
- A census over a TABLE of cases wants both bounds -- `accepted > n` AND `declined > n`;
  `codegen/jvm/GpuOfferDifferentialTest` is the model.
- **The census must not sit downstream of the sizing it checks.**
- **Deriving a shape from the threshold accessors is right and not enough**
  (`am/ik/gpu/MetalGpuTest` is one-backend). **Every threshold you read is either a size or a
  `never`, and the expression must answer sensibly for both** -- branch on the sentinel, or
  clamp before multiplying, and never let `Math.max` with a floor disguise the result.
- `am/ik/gpu/GpuDeclineTest` is "what every machine must do, with a GPU or without", so its
  shapes are deliberately hard-coded; which of its enumerations become free device-present
  pins and which go vacuous differs between Metal and CUDA (`.kb/gpu.md`). When writing a
  device-present sibling, **assert an accepted baseline at the same shape first**.
- Whose arrays the baseline uses is a per-backend fact: SIZE-derived declines need a baseline
  over its OWN arrays with the enumeration operand left fresh (`GpuTest`); STRUCTURE-derived
  declines need the enumeration operand made resident ON PURPOSE (`MetalGpuTest`). CUDA's
  `CudaGemm.stage()` `put`s an input into the residency unconditionally on first sight; an
  accepted Metal `gemm` leaves `isBacked` false on both inputs.
- **Proving a test vacuous takes a mutation, not an argument**: restore the old constant with
  the new census in place; if the value assertions still pass and only the census fails, the
  test was pinning nothing.
