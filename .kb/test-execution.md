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

## A test that never ran the mechanism it asserts on

Not the JVM's discretion this time but the DEVICE's, or the library's -- and the same
shape as the two above: an invariant a test depends on without writing it down. A test
that exercises a mechanism gated by a THRESHOLD has to build a shape that clears the
threshold **that gates the mechanism it is testing**, on the machine it is running on.
When it does not, nothing errors: the gated path declines, the fallback computes the same
answer, and every assertion passes. The test is green and pins nothing.

Three spellings of the mistake, all found in one sweep of the `--gpu` suites
(2026-09-03, `.kb/gpu.md`, "Tests", which has the per-test detail):

- **A shape sized off threshold A while mechanism B is what is under test.** The fused
  row members are gated by their own threshold, which is not the fold's; a test that sized
  its rows off the fold's built 256 rows and the tier declined.
- **A `Long.MAX_VALUE` sentinel put through arithmetic.** "Not a member of that tier at
  all" is spelled as a number, so `2 * threshold` wraps NEGATIVE and
  `(threshold + 383) / 384` likewise, and the `Math.max(floor, ...)` that follows hands
  back the caller's own floor -- a shape below EVERY threshold, which is the opposite of
  what the expression was written to produce. Two independent sites had it.
- **A hard-coded dimension that predates a second backend.** A 64-cube product is 262144
  multiply-adds; one backend's floor is 131072 and the other's is 4194304.

**What to do about it.** Assert the mechanism ran, in the same test, from an observable
the fallback cannot produce:

- Best is a RUNTIME census -- a counter only the accepted path moves. For `--gpu` that is
  a residency lookup, hit or miss (`GpuThresholds.residencyHits()` / `.residencyMisses()`):
  an accepted member asks the cache for each operand and a decline never gets that far.
- Where the mechanism runs in another loader or another process and no counter is
  reachable -- a compiled class carries its own copy of the library -- fall back to
  asserting the SHAPE against the threshold in force
  (`GpuThresholds.acceptedForSize(threshold, elements)`), which at least fails loudly when
  a floor moves under the test.
- A census over a TABLE of cases wants both bounds, not one: `accepted > n` **and**
  `declined > n`, so a table that drifted all-accept is caught as well as one that drifted
  all-decline. `codegen/jvm/GpuOfferDifferentialTest` is the model.

**And the census must not sit downstream of the sizing it is checking.** That suite had
the right census and still failed on Metal, because the operand sizing collapsed first and
an earlier assertion fired. A census answers "did the table pin anything"; it does not
answer "is my operand the size I think it is".

**Deriving a shape from the threshold accessors is right, and is not by itself enough.**
`am/ik/gpu/MetalGpuTest` derives every shape that way and asserts the accept/decline
boolean of every member, so it cannot go vacuous -- but it is a one-backend test, written
where `fold == Long.MAX_VALUE` is a fact in front of the author. A test that runs on BOTH
backends reads the same accessors and gets a sentinel on one of them and a number on the
other, and that is exactly where the arithmetic above wrapped. So for a cross-backend
test the rule is stronger than "derive it": **every threshold you read is either a size or
a `never`, and the expression has to answer sensibly for both** -- branch on the sentinel,
or clamp before multiplying, and never let `Math.max` with a floor disguise the result.

**A suite that is not device-gated runs WITH the device on a machine that has one, and
whether it pins anything there is decided by its fixed shapes.** The counterpart of the
rule above, and it cuts both ways. `am/ik/gpu/GpuDeclineTest` is written as "what every
machine must do, with a GPU or without one" and its shapes are deliberately hard-coded (it
may not size itself off a machine's thresholds, or a GPU-less runner would be testing a
different thing). On a Mac it therefore executes against a live Metal device -- and three
of its enumerations turn out to be the missing "with a device present" pin for free,
because their fixed shapes happen to clear that backend's floors (the element-wise one at
`mapMinElements() * 2`, the strided one at 4096 x 64 = 262144, which is EXACTLY the strided
floor there), while two are vacuous there for the same reason in reverse: the batched
enumeration builds 2097152 units of work against a 4194304 floor and the fused one builds
128 elements, so with hardware present every case in them declines on SIZE and the
condition under test is never reached (`.kb/gpu.md`, "What GpuTest claims, and where Metal
answers it", todo-662). On CUDA the SAME suite splits differently -- the batched enumeration
is a free pin there and the strided one covers the fold as well, while the fused one is
vacuous on both -- so which of its enumerations pin is a per-backend fact and neither
backend's answer transfers (`.kb/gpu.md`, "The same two questions on CUDA"). So: do not read
"runs everywhere" as "pins everywhere", and when you write the device-present sibling,
**assert an accepted baseline at the same shape first** -- that one line is what separates
"this condition declines" from "this shape was never offered".

**Whose arrays the baseline uses is the second decision, and it goes the opposite way
depending on what the enumeration is trying to reach.** Accepting a call can leave its
operands resident, and a resident operand is offered whatever its size, so the baseline can
move the very gate the declines below it are meant to run into. Two shapes of answer, both
in the tree:

- **The baseline over its OWN arrays**, leaving the enumeration's operand fresh -- what an
  enumeration of SIZE-derived declines needs, since a resident operand would be offered past
  the floor being tested (`GpuTest`, todo-663's audit).
- **The enumeration's operand made resident ON PURPOSE, and the baseline taken over it** --
  what an enumeration of STRUCTURE-derived declines needs (an op code with no name, a span
  outside the array it promised, a result array too short, an empty extent). There residency
  is not contamination but the point: it takes the size rule out of the answer, so each
  decline is its own condition's. `MetalGpuTest` does this deliberately, and uses a fresh
  array for the one row that IS size-derived (todo-662).

**Which one a given suite needs is a per-backend fact, because whether an accepted call
adopts its operands at all is.** Measured on 2026-09-03: on CUDA an accepted call left the
operand resident, which is why that audit moved to separate arrays; on Metal an accepted
`gemm` left `isBacked` false on both inputs, because that backend adopts on the SECOND
sight of an unwritten operand, so the same test written the other way was safe there --
by the adoption rule rather than by design. Neither result licenses the other: a suite
that is correct on one backend can be pinning nothing on the other, for this reason as
well as for the threshold reason above.

**Proving one of these is vacuous takes a mutation, not an argument.** Restore the old
constant with the new census in place: if the value assertions still pass and only the
census fails, the test was pinning nothing. That is how each entry in the `.kb/gpu.md`
list was established, and it is cheap.
