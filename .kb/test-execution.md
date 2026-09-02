# Test execution: how the suite actually runs

Cross-cutting rather than backend-specific -- it lives here rather than nested in a
backend's own file because two sessions independently misdiagnosed the same log line as
proof of test parallelism, and a third session finding neither `gpu.md` nor `linalg.md`
would spend the same time again.

## Sequencing: `same_thread` by default, and a fork is a separate JVM

`src/test/resources/junit-platform.properties` sets `parallel.mode.default` and
`parallel.mode.classes.default` to `same_thread`. A test class opts its OWN methods into
concurrent execution only by declaring `@Execution(ExecutionMode.CONCURRENT)` at the class
level; today that is `WasmLispCompilerIntegrationTest` and `RoveTestCommandE2eTest`
directly, and every subclass of `AsdfLibraryE2eSupport` (`Uax15E2eTest` among roughly
thirty other per-library E2E tests) by inheritance. Every other test class -- which is
almost everything, including the whole `am.ik.gpu` / `eval.LinalgGpuTest` family --
runs its methods one at a time, in one thread, in the order JUnit picked.

**The `[rontolisp] JUnit parallelism = N` line printed at the start of a run is NOT
evidence of parallel test execution.** It is `CoreCountParallelismStrategy` printing the
value it derived from the machine's core count for
`junit.jupiter.execution.parallel.config.custom.class`, and that number governs
intra-class parallelism ONLY for a class that opted in above. Two sessions read this line
as "the suite is running N tests at once" and spent time chasing a parallelism race that
was never in play; do not repeat that diagnosis.

`pom.xml`'s surefire configuration runs `forkCount=2` with `reuseForks=true`. A fork is a
separate JVM PROCESS, so nothing that lives in one process's heap -- a weakly-keyed cache,
a static counter, `am.ik.gpu.DeviceResidency`'s live set -- is visible across forks. Two
test classes can only interfere with each other's process-global state by landing in the
SAME fork and running (sequentially, per the paragraph above) in some order within it;
surefire does not guarantee which class lands in which fork or in what order.

## Determinism a test assumes, that is actually the JVM's discretion

Two invariants a test can quietly depend on without writing it down, both of which have
already cost a real test run:

- **A test that asserts an exact `residentBytes()` must KEEP ITS ARRAYS REACHABLE.** A
  resident copy goes when its host array is collected -- that is the design, and
  `aCollectedHostArrayTakesItsResidentCopyWithIt` pins it -- so a total counted over
  arrays the method has stopped referencing is a total the collector may change under the
  assertion. It bit `MetalGpuTest.theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace`
  once in three runs (2026-09-02): six arrays counted, four of them dead to the JIT, the
  assertion reading the two still referenced, and an `a.clone()` on the line above as the
  allocation that triggered it. The rule is `Reference.reachabilityFence` on every array
  the total counts, placed after the last assertion -- and never pass one anonymously
  (`Gpu.map(op, a, 0, new float[n], 0, n)`), which makes it unreachable from the moment it
  exists. "It is used further down, so it is alive" is true today and is not a rule: it
  depends on where the reader happens to put the next statement. The CUDA suite's three
  strict `residentBytes()` assertions were checked against this and survive on that
  accident alone.
- **A PROCESS-WIDE counter's diff around one call is not that call's own effect.**
  `am.ik.gpu.DeviceResidency`'s `dirtyCount()` and `backingCount()` are live-set sizes
  over the same weakly-keyed cache the reachability rule above describes, shared by every
  test that lands in the same fork. Snapshotting one before an operation and asserting
  `before + 1` after it assumes nothing else in the cache changes state in between --
  but an earlier test's now-unreachable entry can be collected AT ANY TIME the collector
  runs, including during the very call under test if that call allocates enough to trigger
  one (`eval`, in particular, always does). `System.gc()` right before the snapshot is a
  hint, not a guarantee, and does not close the window between the snapshot and the
  assertion. `LinalgGpuTest.aDeviceResultStaysOnTheDeviceUntilTheHostFirstReadsIt` failed
  exactly this way in a full-suite run (2026-09-02) surfaced while landing `.todo/644`,
  which was otherwise unrelated to it -- an earlier test's garbage, not a `.todo/644`
  regression. The fix is not a
  tighter window or a re-quiesce immediately before the call -- both only lower the
  odds -- but a PER-HANDLE predicate that asks about the one result under test rather
  than the whole cache: `DeviceResidency.dirty(Object)` / `.backed(Object)`, exposed to
  tests as `GpuThresholds.isDirty(Object)` / `.isBacked(Object)`, ask whether THIS array's
  own entry is dirty or backed, which no unrelated test's garbage can move.
