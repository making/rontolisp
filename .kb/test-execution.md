# Test execution: how the suite actually runs

## Sequencing

- `src/test/resources/junit-platform.properties` sets `parallel.mode.default` and
  `parallel.mode.classes.default` to `same_thread`. A class opts its OWN methods into
  concurrency only with class-level `@Execution(ExecutionMode.CONCURRENT)` (`@Inherited`,
  and it reaches a `@TestFactory`'s dynamic tests too): today
  `WasmLispCompilerIntegrationTest`, `RoveTestCommandE2eTest`, every subclass of
  `AsdfLibraryE2eSupport` (Ironclad, Jose, Rove, Sxql, ...), `ClPostgresE2eTest`,
  `SchemeSpecE2eTest`, `StarlarkSpecE2eTest`, `JavaSpecE2eTest`, `UnclosedOutputFileE2eTest`,
  `eval.LinalgGpuDeclineTest` (its flag-on runs take a lock when a device answered the probe:
  `DeviceResidency` is not thread-safe), `WasmLispCompilerTest`, `cli/RontoLispCliTest`,
  `WasmTreeShakerCorpusTest`, `JvmDeadMethodEliminationCorpusTest` ("Concurrent methods"
  below) and the in-process JVM-backend classes of "Running a compiled class in process,
  concurrently". The price of admission is that no two legs share a file, a table or a
  process-global: a compiled `main` run in process goes through `ThreadStdio`; the
  `ql:quickload` cache every `ClPostgresE2eTest` leg fills is safe because installs are atomic
  (`.kb/dists.md`).
  Everything else -- including all of `am.ik.gpu` / `eval.LinalgGpuTest` -- runs one method
  at a time in one thread. `eval.LinalgGpuTest` costs MINUTES that way on a Mac and carries a
  `@Timeout` so that a run which is merely slow cannot be mistaken for one that stopped
  (`.kb/gpu.md`, "What `eval/LinalgGpuTest` costs").
- Inside ONE method, independent evaluations (own evaluator, own streams) run at once
  through `testsupport/Concurrently`, one thread per core at most: `LinalgGpuDeclineTest`,
  `SimdParallelTest`, `QuantizedMatrixTest`.
- **Trap: `[rontolisp] JUnit parallelism = N` at the start of a run is NOT evidence of
  parallel test execution.** It is `CoreCountParallelismStrategy` printing the value it
  derived for `junit.jupiter.execution.parallel.config.custom.class`, which governs
  intra-class parallelism only for a class that opted in.
- `pom.xml` surefire runs `reuseForks=true` with a fork count sized at test time from the
  FREE cores (processors minus the 1-minute load average, eight per fork, floor 2; the
  `size-test-forks` execution prints it, `-Drontolisp.test.forkCount=N` pins it).
  `CoreCountParallelismStrategy` subtracts the load the same way. With the heavy classes
  concurrent, more forks lose on a saturated box (each fork pays its own JIT warm-up). A fork
  is a separate JVM PROCESS, so nothing in one process's heap (a weakly-keyed cache, a static
  counter, `am.ik.gpu.DeviceResidency`'s live set) is visible across forks; surefire
  guarantees neither which fork nor what order.
- Each fork's heap is `-Xmx6g` in surefire's `argLine`, not the JVM default: the CI runner's
  ergonomics heap (~4 GB on the 16 GB runner) died with `OutOfMemoryError: Java heap space`
  from cumulative pressure, not one bad test. Two forks times 6 GB is the most the 16 GB
  runner affords beside Maven itself and the wasmtime host compiles.

## CI shards

The CI test job is two jobs, `test-e2e` and `test-rest`, each on its own 16 GB runner with
its own 2-fork heap budget. The cut is the `am.ik.rontolisp.e2e` package (subprocess-spawning,
wasmtime compiles, network) versus everything else. The split is CI-only: a local
`./mvnw test` runs the whole suite in one reactor.

`test-e2e` also installs wasm-tools: `JavaSpecE2eTest`'s host-log leg builds a host of
`wasi:logging` from WAT and composes it with a component, and skips without the tool.
`test-rest` has none, so the compositions of `NoGcWasmComponentImportE2eTest` skip there.

Selection is two property-activated surefire profiles in `pom.xml`
(`-Drontolisp.test.scope=e2e` narrows `includes` to `**/e2e/*Test.java`, `=rest` narrows
`excludes` by the same pattern); the base configuration (`forkCount`, `argLine`,
`reuseForks`) is untouched. The profiles exist because `-Dtest` cannot express the cut: an
exclusion-only `-Dtest` resolves every non-test class on the test classpath and died in
discovery on `Bf16NarrowBench` (`NoClassDefFoundError: jdk/incubator/vector/Vector`).
**Both sides use the SAME single-level pattern deliberately**: `**/e2e/**/*Test.java` as an
`includes` entry matches NOTHING (the shard ran 0 tests and passed vacuously), while as an
`excludes` entry it works. Single-level on both sides also fails safe: a future `e2e`
SUBPACKAGE's tests match neither side's pattern, so they run in `test-rest` instead of
silently dropping out of CI -- extend both patterns together if a subpackage ever appears.
Every test class is `*Test.java`, so the two shards partition the classes exactly.

Fork heap peaks (jstat, `forkCount` 2) reach ~5.7 GB in both shards, as one-sample
allocation bursts that are collected: the floor stays `-Xmx6g`, and no shard's heap moves
until its own numbers say so. On CI `test-e2e` runs ~470 fewer tests than locally:
`ClojureDifferentialParityTest`'s factory aborts without `clj` on PATH (environment gating,
not a sharding artifact).

Partition safety: the shards share no checkout, port space or `/tmp`; the only coupling is
the caches. The wasmtime module cache is keyed per module and content-identical for one tree;
the shards still save under separate lineages (`wasmtime-modules-*`, `test-e2e` under
`wasmtime-e2e-modules-*` with a restore-key into the warm full cache) so no save races. Both
shards install OpenBLAS and the same pinned wasmtime, because each owns classes needing them.
The module cache accumulates earlier trees' modules through that restore-key and once evicted
every other cache from the repository's 10 GB budget, so both shards trim the directory to the
newest 3 GiB before the save, and `native-stubs` does not cache its Rust build. `release` and
`examples` chain through the native binary's jobs (`native-image` builds, `native-image-verify`
tests, both `fail-fast: false`, so a failed verify re-runs without a rebuild); both shards run
on pull requests; `examples` never runs on a PR.

**A runner out of memory leaves no report.** The log ends in `Killed` (the `/bin/sh -c`
surefire wraps a fork in, its fork SIGKILLed) and, milliseconds later, `The runner has
received a shutdown signal`: no failed test, no surefire report, the other jobs skipped. So
every `[rontolisp] JUnit parallelism` line, one per class start, carries `memory available`.
The known case: a generated dispatch grew a module eightfold in wasmtime's peak
([clos.md](clos.md), "`change-class`"), the two wasm legs of `AsdfMetaobjectsE2eTest` compiled
at once (4.4 + 7.2 GB) beside `ClPostgresE2eTest`'s CLI JVMs. Surefire's class order differs
run to run, so **a single green run says nothing about the margin**.

The NEXT split, decided, not built: when a shard fills its runner again, split `test-rest` by
package -- the `codegen.*` wasmtime-compile owners versus everything else -- as a third scope
value on the same profile mechanism. Class-name-hash sharding is rejected (surefire ordering
is unspecified and cache keys multiply). If `test-e2e` overflows instead, quarantine its
single heaviest class by name into a third job. `forkCount=3` is not on the path: two 6 GB
forks already fill the runner.

## Running a compiled class in process, concurrently

`JvmLispCompilerTest`, `JvmLinalgSimdAccelCompilerTest`, `JvmSimdAccelCompilerTest`,
`JvmLinalgGpuAccelCompilerTest`, `JvmSimdParallelCompilerTest`, `JvmBFloat16ArrayTest` and
`JvmQuantizedMatrixTest` load each compiled class into its own `URLClassLoader` and call its
`main` in the test JVM, with methods CONCURRENT. What makes that safe:

- **Standard streams: `testsupport/ThreadStdio`.** A compiled class reads
  `System.out`/`err`/`in` with a `getstatic` at every use, so `System.setOut` around
  `main.invoke` is process-wide and two concurrent methods read each other's output.
  `try (var _ = ThreadStdio.out(baos)) { main.invoke(...); }` points the calling thread's
  slot at a target. The slots are `InheritableThreadLocal`s: a thread the program STARTS
  (the sized-stack main, `make-thread`, the `--parallel` workers) writes where its creator
  does. A REUSED thread does not -- the ForkJoin common pool's workers inherit nothing -- and
  its output reaches the process's real stream: missing from the capture, never in another
  test's. **A `System.setOut`/`setIn`/`setErr` in a concurrent class is the bug.**
- Per-method state: scratch files go through the `@TempDir` field (per method under the
  default lifecycle; a full run leaves nothing in the project root), and each program's
  statics -- `_stdinReader`, the embedded SIMD pool, the embedded GPU residency -- live in
  its own loader.
- Process-wide state, locked: `TlsTestSupport.withTrustStore` sets `javax.net.ssl.trustStore*`,
  so its callers hold `@ResourceLock(Resources.SYSTEM_PROPERTIES)` and every other TLS run
  holds it `READ`. A `--gpu` run takes `testsupport/GpuDeviceLock`, a provider that
  serializes while `LinalgGpu.available()` and hands out nothing without a device:
  concurrent use of one device from several embedded `am.ik.gpu` copies is unmeasured.
- Socket tests: `.kb/tcp-sockets.md`, "Socket tests".

**A static field on a supertype that constructs a subtype deadlocks class initialization**
under concurrent tests: `LispFloatArray` (a sealed interface with default methods, so
initialized before any permit, JLS 12.4.2) once held a `WIDTHS` field constructing its permits,
and two threads entered the cycle from opposite ends. Now `LispFloatArray.widths()` over a
holder class, pinned by `LispFloatArrayInitTest`. A hang in a concurrent class is a `jstack`
away from its cause.

## What `WasmLispCompilerIntegrationTest` spends its CPU on

Its ~3,100 `WasmLispCompiler.compile` calls, not wasmtime; the compile-path CPU work is in
`.kb/wasm-ref-type-fold.md`, `.kb/optimize-dead-code-elimination.md`,
`.kb/adding-primitives.md`. **A hot method past 8,000 bytes of bytecode runs interpreted**
(`HugeMethodTest`); that alone was a sizable share of a WASM compile.

## Two builds on one machine: every shared constant collides

Several sessions build this repo at once, one worktree each, and `/tmp` and the port space
belong to the MACHINE. A name built from a constant -- a directory, a file, a port number
-- is therefore shared with every other build running the same test, and the failure looks
like a broken feature rather than a broken harness (seen: dozens of failures in one class
only, each a real feature answering another run's program; `Address already in use`; a
wasmtime **Bus error** reading a module file another run was overwriting).

The rules, and which device to reach for:

- **Every staged file goes through the per-thread, per-PID scratch directory**
  (`WasmLispCompilerIntegrationTest#path`). A `/tmp/...` literal in a test is the bug.
  The per-PID root is `testsupport/ProcessScratch`: deleted at JVM exit, and a killed
  JVM's leftovers are swept by the next one (unswept, they once filled the disk: 6.7 GB).
- **A fixture's Lisp is no exception**: `asdf-metaobjects-demo/classes-tool.lisp`'s `generate-op`
  writes into `target/classes-generated/<random>/`, one directory per load (one per backend leg),
  located from the fixture's `*load-truename*`; `AsdfMetaobjectsE2eTest` deletes the tree after
  the class. Not `/tmp` (shared with every build, and nothing swept it), and not deleted by the
  Lisp itself: the compile path reads the generated file after the load-op returns.
- **A listening port is the kernel's to choose, not the test's.** `wasmtime serve --addr
  127.0.0.1:0` binds an ephemeral port and prints `Serving HTTP on http://127.0.0.1:PORT/`;
  the script reads the port back out of the log (`#awaitServePort`) and nothing is ever
  guessed. Use this wherever the port only has to reach a curl in the same script.
- **Reserving a port with `new ServerSocket(0)` and closing it is the WEAKER device** and
  belongs only where the number must exist before the server does: compiled into a guest
  program, or probed by the Java side after the script exits. The window between the close
  and the real bind spans a whole compile and is wide enough to lose (once in 45 cases
  under three concurrent runs). Such cases go through `#overAReservedPort`, which re-runs on
  a fresh port when the output says `Address already in use`. The script must also WAIT FOR
  ITS OWN BIND (`#awaitServeBound`, the server's `Serving HTTP on` line) before any readiness
  curl: wasmtime compiles before it binds, a loaded suite outlasts any fixed sleep, and the
  port's new owner answers the curl instead.
- A server whose bind failure is not checked turns this into something worse than a red
  test: the losing run connects to the WINNER's server and asserts against it. The TLS
  case did exactly that until its `openssl s_server` log was read back.

Still on the weaker device without a retry: `e2e/ServeComponentE2eSupport#freePort`, and
the `ServerSocket(0)` helpers the Clack / Ningle / Lack E2Es each carry. They reserve much
closer to the bind, so the window is small rather than absent.

## A test that runs a program in the project root

The working directory is a shared constant too, and the worst one: a Java process cannot
change its own, so a test that runs a program IN PROCESS runs it in the project root, where
both surefire forks, every other build on the box and every orphaned one already live.

What it cost on `JvmDeadMethodEliminationCorpusTest`, which compiles the ci-spec corpus twice
and compares the two runs' stdout: one in-process corpus run writes dozens of top-level entries
into the project root (ci-spec cases use RELATIVE paths); its snapshot/delete-what-is-new
cleanup deleted files another process created there meanwhile; and in the other direction
another process deleting the harness-staged `./wpc-sub/` made the second run's
`wild-pathnames` answer `NIL` -- a flake that passed in isolation. `%list-directory` is
`File.list()`, which answers `null` for anything unreadable and never signals
(`.kb/directory-listing.md`), so a vanished directory reads as an empty answer rather than an
error.

**The rule: a test that RUNS a program gives it a working directory that run owns.** In
process that is impossible, so the program goes in a SUBPROCESS with `ProcessBuilder#directory`
-- which is what `JvmDeadMethodEliminationCorpusTest` does, one fresh `@TempDir` child per run
(`CiSpecE2eTest` always did). Both runs then start from the same staged state, the verifier
check is a real JVM launch rather than a `URLClassLoader`, and there is nothing to clean up:
the class asserts the project root gained nothing, and `CorpusFixtures` carries no
remove-what-is-new pair for anyone to reach for.

## Concurrent methods

The slowest classes were each one long method, so neither fork could overlap their work:

- `WasmTreeShakerCorpusTest`: a parameterized method per WASI mode, concurrent; the three
  levels compile side by side (`COMPILE_THREADS`, a quarter of the cores, 1..3) and every
  `wasm-tools` check is its own child process. Six in flight fit the 6 GB fork only because
  `WasmRefTypeFolder` does not cache decoded bodies (`.kb/wasm-ref-type-fold.md`).
- `JvmDeadMethodEliminationCorpusTest` absorbed the OSR backedge guard (it compiled the
  identical program at the identical two levels): one front end, the two compiles side by
  side, the two runs side by side (each owns its directory).
- `cli/RontoLispCliTest`: concurrent; the methods that capture `System.err`/`System.out` live
  in `cli/RontoLispCliStreamsTest`, which stays sequential.
- `WasmLispCompilerTest`: concurrent (in-memory compiles only).

Traps:

- **`System.err` is one slot.** A capture by `setErr` in a concurrent method sees every other
  method's output and restores over another's capture. `testsupport/UndefinedWarnings`
  routes by THREAD instead (a compile runs on its caller's thread). A method that must read
  the process stream belongs in a sequential class of its own.
- **`@Isolated` on a `@Nested` class serializes the WHOLE enclosing class**, not just the nested
  one: JUnit moves the global read-write lock to the top-level class and forces its
  descendants to `SAME_THREAD` (wall time becomes the sum of the methods). Class-level `READ`
  locks with method-level `READ_WRITE` on `System.err` convoyed nearly as badly. A separate
  top-level class costs nothing: classes run one at a time per fork.
- **A `@TempDir` FIELD under `@TestInstance(PER_CLASS)` is re-injected and deleted per method.**
  Concurrent methods share the field, so one method's cleanup deletes the directory another is
  running in (seen as `NoSuchFileException` on a file the test had just created). Take it as a
  method parameter.
- **A leg's directory named after something two cases share is a shared file.** Spec E2E
  tests key a case's directories by its unique name, never by its file: `JavaSpecE2eTest`
  named them by the file, three standalone cases are `app/Main.java`, and a JDK leg ran
  another case's program once in a full run (the class alone passed). Its `standalone`
  factory asserts the stems are distinct.

## No test reads a clock to judge cost

No pin bounds one elapsed time by another or by a constant: on a loaded 4-vCPU runner with
methods CONCURRENT such a ratio was in effect a ~500 ms absolute budget, failed CI 22 times
with no product change, and several could not fail on the regression they named
(`.kb/character-sequence-io.md`). A cost invariant is pinned by something the machine cannot
move:

- the mechanism's own state or shape -- the memory slots of the compiled class
  (`.kb/string-index-cost.md`), a helper call counted in the bytecode or the module
  (`ownCallsIn`, `WasmLispCompilerTest#storeCalls`), a library defun's source shape, a
  table's order slots (`RontoHashTableTest`);
- a wasmtime FUEL budget (`WasmLispCompilerIntegrationTest#compileAndRunWithFuel`): an
  instruction count, identical on every machine. Size the program so the fixed cost sits
  several times under the budget and the regression orders of magnitude over it, and write
  the measured fuel beside the budget. Blind spot: one `array.copy` / `memory.copy` costs the
  same fuel whatever its length;
- an allocation budget (`com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes`
  around one call on the current thread);
- for concurrency, a latch or a thread's state (`Thread.getState`, `ThreadMXBean`'s locked
  monitors), never "finished within N ms". A timeout is a generous hang guard only.

Lower bounds on a sleep (`(sleep 0.05)` took at least 40 ms) stay: the platform guarantees
them.

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

## In-process program work runs on the CLI's stack, not JUnit's

The CLI runs the whole command line -- the interpreter AND the compile path's passes and
backend -- on a thread of 16 MiB (`SizedThread.WORKER_STACK_BYTES`,
[interpreter-stack.md](interpreter-stack.md)). A JUnit worker carries the JVM default, 1 MiB
on linux-x64, so a test that interprets or compiles IN PROCESS would measure the harness's
ceiling instead of the product's. `testsupport/CliStack` runs a body on a thread of
`CliStack.BYTES` and rethrows what it threw as itself (`call`; `callWithin` adds a wall-clock
cap and abandons a body still running); `testsupport/CliStackExtension`
(`@ExtendWith(CliStackExtension.class)`) moves every test method body of a class there, with
lifecycle methods and resource locks left on the JUnit worker. `RontoLispCliTest` pins
`CliStack.BYTES` to the CLI's default. A `StackOverflowError` from a leg on this stack is a
real depth regression, not a stack-size accident.

Users: the interpreter legs of `AsdfLibraryE2eSupport` (cl-mustache's spec suite alone
recurses past 1 MiB), `SchemeSpecE2eTest`, `StarlarkSpecE2eTest` and `SicpCorpusE2eTest`, and
-- by the extension -- the in-process JVM-backend classes `JvmLispCompilerTest`,
`JvmBFloat16ArrayTest`, `JvmLinalgGpuAccelCompilerTest`, `JvmLinalgSimdAccelCompilerTest`,
`JvmQuantizedMatrixTest`, `JvmSimdAccelCompilerTest` and `JvmSimdParallelCompilerTest`.

**How much stack a depth costs depends on the JIT, so the failure is order-dependent.** With
parallel execution enabled every test, `SAME_THREAD` or not, runs on a ForkJoin worker of the
default size; a wide program meeting a recursing pass before the JIT has compiled it overflows
(an interpreted frame is several times a compiled one) in a full run and passes alone
(`JvmLispCompilerTest#compileAndRunABranchSpanningPastTheSigned16BitOffset`, one `progn` of
2,800 forms, overflowed in `CompileTimeBoundp.scan`). Walks
that recurse on the cdr make the depth the LIST's LENGTH, not the program's nesting: such walks
loop down the spine and an embedder compiles on the CLI's stack
([interpreter-stack.md](interpreter-stack.md), "Depth is NESTING"); `WideListStackTest`
compiles 50,000-element lists on 1 MiB.

The evaluator's own per-form scans stay off that budget too: the typecase arm's uiop /
asdf / geom name scans (`LispEvaluator#collectUiopNames`,
`AsdfRuntimeLibrary#mentionsComponentClass`, `GeomLibrary#mentionsGeomClass`) walk the
cdr spine in a LOOP, since they run at whatever depth the program has already reached and
a frame per list element would spend stack the program still needs.

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

## Decoded text and the argmax alarm

A model's decode is the one output a value, a shape or a length assertion cannot police.
One moved argmax -- from a reduction order, an accumulator boundary or a routing decision
-- is a different sentence that still reads like English, while every count and every
tensor shape around it stays exactly as it was. The alarm has to be the TEXT.

Three pins, in the order a lane reaches them:

- **`ci-spec.yaml`: `transformer-greedy-decode-text-cross-backend` and
  `gated-delta-rule-greedy-decode-text-cross-backend`.** Seeded synthetic models -- a
  1-layer dim-128 transformer over a KV cache, and a 2-head gated delta rule whose state
  is carried across tokens -- decoded greedily, printing the token ids, the words and the
  top-2 logit margin in thousandths. Four backends x scalar/`--simd`, on every push AND
  every pull request, and the corpus's only `vec:` shapes above the SIMD length gates
  (`.kb/vec.md`). Untrained, so the words are garbage; garbage that changes when a
  reduction order moves is the same alarm a trained model gives. All eight legs agree on
  every id, word and printed margin, with ample headroom at the printed thousandth.
- **`examples/examples.yaml`: `llm/llm.lisp` over the checked-in stories260K** (and
  stories15M when it has been downloaded), `equals` against `run.c`'s own text. This is
  the REAL-checkpoint pin, and the reason the synthetic ones exist rather than replace it
  -- `ExamplesE2eTest` `needs: release`, so it never runs on a pull request, it is the one
  job allowed to be red, and `./mvnw test` skips it. The real decode is far too slow on the
  scalar interpreter for an always-run lane, and the ci-spec driver cannot stage its 1 MB
  checkpoint.
- **`examples/llm/deltanet-check.lisp` and `shortconv-check.lisp`**: the layer arithmetic
  against a float64 transcription of the PyTorch reference, same job and same caveats.

**What none of them covers**: no published checkpoint's decode is pinned anywhere CI can
run -- TinyLlama, SmolLM2, Qwen3-0.6B and Qwen3.5-0.8B are gigabytes and outside the repo
by design. Qwen3.5-0.8B's greedy answer is written down in `examples/llm/README.md`, which
is findable by someone who thinks to look and is strictly weaker than detectable. A
synthetic Gated DeltaNet fixture is the closest the repo gets, and it pins the
recurrence's SHAPE, not a trained model's weights.

## A single-backend test the corpus already pins

`ci-spec.yaml` runs every case on all four backends (x `--simd`) in CI's native job, so a
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` / `LispEvaluatorTest` method whose
whole program sits inside one case's `source` and whose expected output inside its `expected`
pins nothing the corpus does not. Keep: methods a `.kb` file or a source comment names, a case
with `expectedByBackend`, any other assertion. The cost is the local loop: `./mvnw test` skips
`CiSpecE2eTest`, so a regression there surfaces in CI or in the native E2E run
(`.kb/running-backends.md`). Add a case to the corpus rather than a per-backend method.

Moving a method into the corpus, the rules:

- The corpus is ONE program per backend, so a moved case must define no name another case
  defines: a compiled backend resolves a `defun` across the whole program, and a collision
  breaks EARLIER cases on every leg (`Function expects 1 argument, got 0`). Also stays in
  Java: a name an existing case already has, output depending on global counters (`gensym`),
  programs a WASM target cannot build or run (threads, TCP, TLS servers, `getenv`,
  `(set nil 1)`), and output that changes only inside the whole corpus.
- A YAML `expected` that ends in a blank line needs `|+`; plain `|` drops it.

## A case that ends the corpus, a case that leaks into the next

The corpus is one program, so a case that ends it -- a `quit`, an uncaught error, an upstream
`restore-image` -- fails every case after it on every leg (half the corpus, once), and only a
native run showed it. **`JvmDeadMethodEliminationCorpusTest` asserts the JVM run printed the
spec's line count**: it already compiles and runs the whole corpus in `./mvnw test`, so the
check costs nothing. It sees the ending, not a wrong line: cases still pin output only in
`CiSpecE2eTest`.

A case that leaves global state another case reads (`*modules*`, a package, a feature) is not
caught that way: the interpreter leg runs `cases:` in one process and the compiled legs see the
whole program (seen: a `provide` reaching a later `(print *modules*)`; an order that exposed a
real two-argument `intern` bug, [symbol-runtime-api.md](symbol-runtime-api.md)). Running each
case alone (one process per case) is rejected as too dear. The rules instead: a case undoes
what it adds to a shared registry, and reads a registry by the entry it cares about
(`(member "SWANK-INDENTATION" *modules* ...)`), never whole. To find which case broke a later
one, drop leading cases off the concatenated program until the later one passes: the last
dropped is the culprit.

## One served program of the HTTP server corpus

`FetchSpecE2eTest#javaHttpServer` runs every program of `java-http-server-spec.yaml` on six
legs; `-Drontolisp.fetch-spec.only=<name>` runs one (`peers`: 12 s against the whole
factory's minutes). No other factory of the class reads it.
