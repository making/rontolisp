# Test execution: how the suite actually runs

## Sequencing

- `src/test/resources/junit-platform.properties` sets `parallel.mode.default` and
  `parallel.mode.classes.default` to `same_thread`. A class opts its OWN methods into
  concurrency only with class-level `@Execution(ExecutionMode.CONCURRENT)` (`@Inherited`,
  and it reaches a `@TestFactory`'s dynamic tests too): today
  `WasmLispCompilerIntegrationTest`, `RoveTestCommandE2eTest`, every subclass of
  `AsdfLibraryE2eSupport` (Ironclad, Jose, Rove, Sxql, ...), `ClPostgresE2eTest`,
  `SchemeSpecE2eTest`, `UnclosedOutputFileE2eTest`, `eval.LinalgGpuDeclineTest` (its
  flag-on runs take a lock when a device answered the probe: `DeviceResidency` is not
  thread-safe), `WasmLispCompilerTest`, `cli/RontoLispCliTest`, `WasmTreeShakerCorpusTest`,
  `JvmClassShakerCorpusTest` ("Concurrent methods" below) and the in-process JVM-backend
  classes of "Running a compiled class in process, concurrently". The price of admission
  is that no two legs share a file, a table or a process-global: a compiled `main` run in
  process goes through `ThreadStdio`; the `ql:quickload` cache every `ClPostgresE2eTest`
  leg fills is safe because installs are atomic (`.kb/dists.md`). Measured alone
  2026-09-24 (64 cores): `ClPostgresE2eTest` 292 s -> 57 s (cold cache 58 s),
  `SchemeSpecE2eTest` 176 s -> 77 s (its four corpus runs also start at once; the
  interpreter's is the floor), `UnclosedOutputFileE2eTest` 15 s -> 5 s.
  Everything else -- including all of `am.ik.gpu` /
  `eval.LinalgGpuTest` -- runs one method at a time in one thread. `eval.LinalgGpuTest`
  costs MINUTES that way on a Mac and carries a `@Timeout` so that a run which is merely
  slow cannot be mistaken for one that stopped (`.kb/gpu.md`, "What `eval/LinalgGpuTest`
  costs").
- Inside ONE method, independent evaluations (own evaluator, own streams) run at once
  through `testsupport/Concurrently`, one thread per core at most: `LinalgGpuDeclineTest`,
  `SimdParallelTest`, `QuantizedMatrixTest`. Measured 2026-09-24 on the 64-core box, alone:
  281 -> 31 s, 16 -> 4 s, 16 -> 6 s.
- **Trap: `[rontolisp] JUnit parallelism = N` at the start of a run is NOT evidence of
  parallel test execution.** It is `CoreCountParallelismStrategy` printing the value it
  derived for `junit.jupiter.execution.parallel.config.custom.class`, which governs
  intra-class parallelism only for a class that opted in.
- `pom.xml` surefire runs `reuseForks=true` with a fork count sized at test time from the
  FREE cores (processors minus the 1-minute load average, eight per fork, floor 2; the
  `size-test-forks` execution prints it, `-Drontolisp.test.forkCount=N` pins it).
  `CoreCountParallelismStrategy` subtracts the load the same way. Measured 2026-09-24 on
  the 64-thread / 32-core box: before the heavy classes went concurrent, 2 forks took
  20m51s and 32 forks 9m00s; after, the test phase took 408 s at 29 forks, 372 s at 16,
  268 s at 8, 332 s at 4 (4 started under load 32). More forks lose once the classes are
  concurrent: the box is saturated and each fork pays its own JIT warm-up (CPU 156 min at
  8 forks against 201 at 29). A fork is a separate JVM PROCESS,
  so nothing in one process's heap (a weakly-keyed cache, a static counter,
  `am.ik.gpu.DeviceResidency`'s live set) is visible across forks; surefire guarantees neither
  which fork nor what order.

## Running a compiled class in process, concurrently

`JvmLispCompilerTest`, `JvmLinalgSimdAccelCompilerTest`, `JvmSimdAccelCompilerTest`,
`JvmLinalgGpuAccelCompilerTest`, `JvmSimdParallelCompilerTest`, `JvmBFloat16ArrayTest` and
`JvmQuantizedMatrixTest` load each compiled class into its own `URLClassLoader` and call its
`main` in the test JVM, with methods CONCURRENT. What makes that safe:

- **Standard streams: `testsupport/ThreadStdio`.** A compiled class reads
  `System.out`/`err`/`in` with a `getstatic` at every use, so `System.setOut` around
  `main.invoke` is process-wide and two concurrent methods read each other's output.
  `ThreadStdio` installs one routing stream per slot, once, and
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
- Still exposed: the two `tls-listen` tests reserve a port with `TlsTestSupport.freePort`
  (the weaker device of the next section), now with same-JVM competitors; the cost-ratio
  pins (`assertScanIsFlat` and siblings) compare two halves of ONE run, with 500 ms + 6x
  slack.

Found on the way, **2026-09-24**: the first concurrent run of `JvmLinalgSimdAccelCompilerTest`
hung forever on a class-initialization deadlock in production code. `LispFloatArray` (a
sealed interface with default methods, so initialized before any permit, JLS 12.4.2) held a
`WIDTHS` field constructing its permits; one thread reading a `#bf16(...)` literal and one
compiling a `concatenate` entered the cycle from opposite ends. Now `LispFloatArray.widths()`
over a holder class, pinned by `LispFloatArrayInitTest`. **A static field on a supertype
that constructs a subtype is the same bug**; a hang in a concurrent class is a `jstack`
away from its cause.

Measured 2026-09-24 on this box (64 cores, parallelism 16), each class alone, surefire's
suite time, sequential -> concurrent (range of four concurrent runs, all green):

| class | tests | sequential | concurrent |
|---|---|---|---|
| `JvmLispCompilerTest` | 1354 | 282 s | 28-30 s |
| `JvmLinalgSimdAccelCompilerTest` | 37 | 171 s | 44-47 s |
| `JvmSimdAccelCompilerTest` | 40 | 62 s | 20-22 s |
| `JvmLinalgGpuAccelCompilerTest` (no device) | 44 | 54 s | 14-16 s |
| `JvmSimdParallelCompilerTest` | 9 | 26 s | 8-10 s |
| `JvmBFloat16ArrayTest` | 30 | 24 s | 8 s |
| `JvmQuantizedMatrixTest` | 10 | 24 s | 14-15 s |

## What `WasmLispCompilerIntegrationTest` spends its CPU on

Its ~3,100 `WasmLispCompiler.compile` calls, not wasmtime: measured 2026-09-24 with a
per-thread CPU probe around `compile`, 713-728 CPU-s of the class's ~1,650 (user+sys), and a
JFR profile put ~40% of that in `shakeCore`'s passes over the pre-shake module (which carries
the whole runtime: ~240 KB for a 3-line program, ~5.6 KB after the shake). After the
compile-CPU series (dead-body skipping in the sink and the fold, the JIT-sized operator
dispatch, the worklist fold, the scope memos -- `.kb/wasm-ref-type-fold.md`,
`.kb/optimize-dead-code-elimination.md`, `.kb/adding-primitives.md`): **427 CPU-s** of
compile, the class 1,320-1,390 -> ~1,025 CPU-s and 54-61 s -> 40-42 s of its own elapsed
time on the same machine. **A hot method past 8,000 bytes of bytecode runs interpreted**
(`HugeMethodTest`); that alone was 12% of a WASM compile.

## Two builds on one machine: every shared constant collides

Several sessions build this repo at once, one worktree each, and `/tmp` and the port space
belong to the MACHINE. A name built from a constant -- a directory, a file, a port number
-- is therefore shared with every other build running the same test, and the failure looks
like a broken feature rather than a broken harness.

What it costs, measured:

- **2026-09-11**: `WasmLispCompilerIntegrationTest`'s scratch root was
  `/tmp/rontolisp-wasmtime/w<threadId>` -- unique inside one JVM, identical across JVMs.
  Two full suites in two worktrees gave **39 failures in that class and nowhere else**,
  each one a real feature answering another run's program. Fixed by qualifying with
  `ProcessHandle.current().pid()` (`.todo/781`).
- **2026-09-12**: with that fixed, two concurrent runs of that class's `wasmtime serve`
  family still failed **3 and 14 of 15** cases. Every one of them named a constant the
  method bodies held: a hardcoded port (`Address already in use`) or a hardcoded
  `/tmp/serve-*.wasm` (one run's `wasmtime` took a **Bus error** reading a module file the
  other run was overwriting under it). Fixed by `.todo/787`.

The rules that follow, and which device to reach for:

- **Every staged file goes through the per-thread, per-PID scratch directory**
  (`WasmLispCompilerIntegrationTest#path`). A `/tmp/...` literal in a test is the bug.
  The per-PID root is `testsupport/ProcessScratch`: deleted at JVM exit, and a killed
  JVM's leftovers are swept by the next one (before it, 10,150 stale directories, 6.7 GB,
  filled the development machine's disk on 2026-09-24).
- **A listening port is the kernel's to choose, not the test's.** `wasmtime serve --addr
  127.0.0.1:0` binds an ephemeral port and prints `Serving HTTP on http://127.0.0.1:PORT/`;
  the script reads the port back out of the log (`#awaitServePort`) and nothing is ever
  guessed. Use this wherever the port only has to reach a curl in the same script.
- **Reserving a port with `new ServerSocket(0)` and closing it is the WEAKER device** and
  belongs only where the number must exist before the server does: compiled into a guest
  program, or probed by the Java side after the script exits. The window between the close
  and the real bind then spans a whole compile, and it is wide enough to lose -- measured
  2026-09-12, three concurrent runs of that serve family lost it **once in 45 cases**.
  Such cases go through `#overAReservedPort`, which re-runs on a fresh port when the
  output says `Address already in use`. The script must also WAIT FOR ITS OWN BIND
  (`#awaitServeBound`, the server's `Serving HTTP on` line) before any readiness curl:
  wasmtime compiles before it binds, a loaded suite outlasts any fixed sleep, and the
  port's new owner answers the curl instead (2026-09-24, at 6 forks: a proxy case relayed
  another server's empty 200 and printed `proxied  200`).
- A server whose bind failure is not checked turns this into something worse than a red
  test: the losing run connects to the WINNER's server and asserts against it. The TLS
  case did exactly that until its `openssl s_server` log was read back.

Still on the weaker device without a retry: `e2e/ServeComponentE2eSupport#freePort`, and
the `ServerSocket(0)` helpers the Clack / Ningle / Lack E2Es each carry. They reserve much
closer to the bind than the WASM class did, so the window is small rather than absent.

## A test that runs a program in the project root

The working directory is a shared constant too, and the worst one: a Java process cannot
change its own, so a test that runs a program IN PROCESS runs it in the project root, where
both surefire forks, every other build on the box and every orphaned one already live.

**Measured 2026-09-19** on `JvmClassShakerCorpusTest`, which compiled the ci-spec corpus twice
and compared the two runs' stdout:

- **One in-process corpus run writes 37 top-level entries into the project root** (`dls-a.txt`,
  `probe.dat`, `w257/`, `ci-model.gguf`, a `tmp<random>.tmp`, ...), because dozens of ci-spec
  cases use RELATIVE paths.
- Its cleanup was `snapshotTopLevel` before / delete-every-new-entry after. Demonstrated
  deterministically: a file AND a directory created by another shell in the project root while
  the class ran were both gone, recursively, when it finished. Anything the other fork wrote
  there in that ~70 s window was collateral.
- The other direction is the flake this was found through. A full run went red in that class
  alone, passed it twice in isolation and passed the suite on a re-run, so it was landed as
  noise (`.todo/914`). The recorded assertion message shows the two 4519-line outputs differing
  on **exactly one line**: the `wild-pathnames` case answered
  `(#P"./wpc-sub/wpc-a.txt" #P"./wpc-sub/wpc-b.txt")` in the first run and `NIL` in the second,
  i.e. the harness-staged `./wpc-sub/` was gone by the time the second run walked it. Nothing
  in the repo deletes that tree except this class's own cleanup, so the deleter was another
  process sharing the directory -- a second fork, another checkout's build, or an orphaned one.
  Reproduced exactly (one differing line, same text) by removing `./wpc-sub/` between the two
  runs. `%list-directory` is `File.list()`, which answers `null` for anything unreadable and
  never signals (`.kb/directory-listing.md`), so a vanished directory reads as an empty answer
  rather than an error.

**The rule: a test that RUNS a program gives it a working directory that run owns.** In
process that is impossible, so the program goes in a SUBPROCESS with `ProcessBuilder#directory`
-- which is what `JvmClassShakerCorpusTest` now does, one fresh `@TempDir` child per run
(`CiSpecE2eTest` always did). Both runs then start from the same staged state instead of the
second inheriting the first's scratch files, the verifier check is a real JVM launch rather
than a `URLClassLoader`, and there is nothing to clean up: the class asserts the project root
gained nothing, and `CorpusFixtures` no longer carries a remove-what-is-new pair for anyone to
reach for. Cost, measured on this box: unchanged, 64 s against 69 s in process.

## Concurrent methods (2026-09-24)

The slowest classes were each one long method, so neither fork could overlap their work.
Measured alone on this box (64 cores, shared with other builds), before -> after:

| Class | Before | After | What changed |
|---|---|---|---|
| `WasmTreeShakerCorpusTest` | 91-100 s | 30 s | a parameterized method per WASI mode, concurrent; the three levels compile side by side (`COMPILE_THREADS`, a quarter of the cores, 1..3) and every `wasm-tools` check is its own child process |
| `JvmClassShakerCorpusTest` + `JvmOsrBackedgeCorpusTest` | 69-73 s + 37-39 s | 40 s | merged: the OSR guard compiled the identical program at the identical two levels; one front end, the two compiles side by side, the two runs side by side (each owns its directory) |
| `DocExamplesTest` | 75 s | 34 s | `scheme/eval.md` looped 100,000 times inside Scheme `eval` on the interpreter, 20 s per language (`scheme-spec.yaml` pins the 100,000 on all four backends); plus `PackageRegistry` below |
| `cli/RontoLispCliTest` | 15-17 s | 6 s + 5 s | concurrent; the 30 methods that capture `System.err`/`System.out` moved to `cli/RontoLispCliStreamsTest`, which stays sequential |
| `WasmLispCompilerTest` | 26-29 s | 9 s | concurrent (in-memory compiles only) |
| `PackageCycleTest` | 12-14 s | 1.2 s | a regex compiled and run per same-package neighbour per class (quadratic) replaced by one word set per class |
| `cli/CompileIndependenceTest` | 24-29 s | 25 s | the prelude fix below; its phases stay as designed |

Product fixes found on the way (each measured by JFR on the corpus compile or the test):
`ClosRegistry.printObjectTags` memoized (10-14% of a corpus compile, recomputed per print
site); `LispMacroExpander.injectMvSpillGlobal` answers its ~40 "does the program name X"
questions from one symbol census (~5%); `LispPreludeLibrary.process` does the same across its
selection fixpoint (64% of the front end of an ASDF program; the corpus front end went from
~2 s to ~0.7 s warm); `PackageRegistry` builds the built-in packages once and hands each
registry copy-on-write member tables (`LispPackage.MemberTable`; 12% of `DocExamplesTest`).

Not changed, measured: `WasmRefTypeFolder.fold` is ~55% of a DEFAULT-level corpus WASM
compile (3-4 s): 8 round-robin rounds over ~4,700 functions to its fixpoint. A worklist is
the fix; the final full rewrite walk already re-verifies the fixpoint, so an incomplete
dependency index would cost rounds, not answers.

Traps met doing it:

- **`System.err` is one slot.** A capture by `setErr` in a concurrent method sees every other
  method's output and restores over another's capture. `testsupport/UndefinedWarnings`
  routes by THREAD instead (a compile runs on its caller's thread). A method that must read
  the process stream belongs in a sequential class of its own.
- **`@Isolated` on a `@Nested` class serializes the WHOLE enclosing class**, not just the nested
  one: JUnit moves the global read-write lock to the top-level class and forces its
  descendants to `SAME_THREAD`. Measured: 119 methods, 23.5 s wall = the sum of the methods.
  Class-level `READ` locks with method-level `READ_WRITE` on `System.err` convoyed nearly as
  badly (20 s). A separate top-level class costs nothing: classes run one at a time per fork.
- **A `@TempDir` FIELD under `@TestInstance(PER_CLASS)` is re-injected and deleted per method.**
  Concurrent methods share the field, so one method's cleanup deletes the directory another is
  running in (seen as `NoSuchFileException` on a file the test had just created). Take it as a
  method parameter.

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
backend -- on a thread of 16 MiB (`RontoLispCli`'s `WORKER_STACK_BYTES`,
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
recurses past 1 MiB), `SchemeSpecE2eTest` and `SicpCorpusE2eTest`, and -- by the extension --
the in-process JVM-backend classes `JvmLispCompilerTest`, `JvmBFloat16ArrayTest`,
`JvmLinalgGpuAccelCompilerTest`, `JvmLinalgSimdAccelCompilerTest`, `JvmQuantizedMatrixTest`,
`JvmSimdAccelCompilerTest` and `JvmSimdParallelCompilerTest`.

**How much stack a depth costs depends on the JIT, so the failure is order-dependent.**
Seen 2026-09-24: `JvmLispCompilerTest#compileAndRunABranchSpanningPastTheSigned16BitOffset`
(one `progn` of 2,800 forms) overflowed in `CompileTimeBoundp.scan` in a full run and passed
alone. The stack had not changed -- with parallel execution enabled every test, `SAME_THREAD`
or not, runs on a ForkJoin worker of the default size -- but once the class ran its methods
concurrently the wide program could meet the recursing pass before the JIT had compiled it,
and an interpreted frame is several times a compiled one. Measured on a fresh JVM, a thread
of the given size compiling that program through `JvmLispCompiler`: 256 KiB overflows in
`PackageResolver.referencesRuntimePackageMutation` (~920 frames), 512 KiB in
`UiopLibrary.collectSymbols` (~1,020), 1 MiB in `CompileTimeBoundp.scan` (~1,020), 2 MiB
passes. Every one of them recurses on the cdr, so the depth is the LIST's LENGTH, not the
program's nesting; `src/main/java` has ~216 such self-recursive `.cdr()` walks. Through the
full front end (`JvmSourceCompiler`, what an embedder such as the Maven plugin calls on its own
thread) at 1 MiB: 700 and 1,400 forms pass, 2,800 overflows (`JsonLibrary$Walker.rewrite`),
20,000 overflows first in `AsdfRuntimeLibrary.referencesRuntime`.

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
  reduction order moves is the same alarm a trained model gives.
- **`examples/examples.yaml`: `llm/llm.lisp` over the checked-in stories260K** (and
  stories15M when it has been downloaded), `equals` against `run.c`'s own text. This is
  the REAL-checkpoint pin, and the reason the synthetic ones exist rather than replace it
  -- `ExamplesE2eTest` `needs: release`, so it never runs on a pull request, it is the one
  job allowed to be red, and `./mvnw test` skips it.
- **`examples/llm/deltanet-check.lisp` and `shortconv-check.lisp`**: the layer arithmetic
  against a float64 transcription of the PyTorch reference, same job and same caveats.

Measured 2026-09-06 (this box, native binary):

- stories260K, 40 greedy tokens, scalar interpreter: **39 s**. That, plus llm.lisp's
  engine and a 1 MB checkpoint the ci-spec driver has no way to stage, is why the
  always-run lane carries synthetic models instead of the real one.
- the two synthetic cases: **4.3 s** of the scalar interpreter leg, under a second on each
  other leg; the whole `CiSpecE2eTest` 111.9 s / 4020 tests -> **113.7 s / 4076**.
- all eight legs agree on every id, every word and every printed margin; at 1e-6
  resolution one of the ten margins differs by ONE unit between legs, against a smallest
  margin of 0.091 -- so the printed thousandth has ~1000x headroom and the argmax ~91000x.

**What none of them covers**: no published checkpoint's decode is pinned anywhere CI can
run -- TinyLlama, SmolLM2, Qwen3-0.6B and Qwen3.5-0.8B are gigabytes and outside the repo
by design. Qwen3.5-0.8B's greedy answer is written down in `examples/llm/README.md`, which
is findable by someone who thinks to look and is strictly weaker than detectable. A
synthetic Gated DeltaNet fixture is the closest the repo gets, and it pins the
recurrence's SHAPE, not a trained model's weights.
