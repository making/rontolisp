# Test execution: how the suite actually runs

## Sequencing

- `src/test/resources/junit-platform.properties` sets `parallel.mode.default` and
  `parallel.mode.classes.default` to `same_thread`. A class opts its OWN methods into
  concurrency only with class-level `@Execution(ExecutionMode.CONCURRENT)` (`@Inherited`,
  and it reaches a `@TestFactory`'s dynamic tests too): today
  `WasmLispCompilerIntegrationTest`, `RoveTestCommandE2eTest`, every subclass of
  `AsdfLibraryE2eSupport` (Ironclad, Jose, Rove, Sxql, ...), `ClPostgresE2eTest`,
  `SchemeSpecE2eTest` and `UnclosedOutputFileE2eTest`. The price of admission is that no
  two legs share a file, a table or a process-global: `SchemeSpecE2eTest` runs a compiled
  `main` in process, so the factories that swap `System.out`/`System.in` hold
  `@ResourceLock(Resources.SYSTEM_OUT)`; the `ql:quickload` cache every
  `ClPostgresE2eTest` leg fills is safe because installs are atomic (`.kb/dists.md`).
  Measured alone 2026-09-24 (64 cores): `ClPostgresE2eTest` 292 s -> 57 s (cold cache
  58 s), `SchemeSpecE2eTest` 176 s -> 81 s (its four corpus runs also start at once;
  the interpreter's is the floor), `UnclosedOutputFileE2eTest` 15 s -> 5 s.
  Everything else -- including all of `am.ik.gpu` /
  `eval.LinalgGpuTest` -- runs one method at a time in one thread. `eval.LinalgGpuTest`
  costs MINUTES that way on a Mac and carries a `@Timeout` so that a run which is merely
  slow cannot be mistaken for one that stopped (`.kb/gpu.md`, "What `eval/LinalgGpuTest`
  costs").
- **Trap: `[rontolisp] JUnit parallelism = N` at the start of a run is NOT evidence of
  parallel test execution.** It is `CoreCountParallelismStrategy` printing the value it
  derived for `junit.jupiter.execution.parallel.config.custom.class`, which governs
  intra-class parallelism only for a class that opted in.
- `pom.xml` surefire runs `reuseForks=true` with a fork count sized at test time from the
  FREE cores (processors minus the 1-minute load average, two per fork, floor 2, cap half
  the cores; the `size-test-forks` execution prints it, `-Drontolisp.test.forkCount=N`
  pins it). `CoreCountParallelismStrategy` subtracts the load the same way. Measured
  2026-09-24 on the idle 64-core box: 2 forks 20m51s, 32 forks 9m00s -- and with that
  many forks the wall clock is the longest single class, so heavy classes run their own
  methods concurrently. A fork is a separate JVM PROCESS,
  so nothing in one process's heap (a weakly-keyed cache, a static counter,
  `am.ik.gpu.DeviceResidency`'s live set) is visible across forks; surefire guarantees neither
  which fork nor what order.

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
  output says `Address already in use`.
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

## The in-process interpreter leg runs on the CLI's stack, not JUnit's

`AsdfLibraryE2eSupport#loadsAndRunsOnTheInterpreter` drives `LispEvaluator` IN PROCESS,
so without help it recurses on the JUnit worker thread -- the JVM default stack, 1 MiB on
linux-x64, which cl-mustache's spec suite alone recurses past
([interpreter-stack.md](interpreter-stack.md) has the numbers). The leg therefore runs its
body on a thread with the stack the CLI hands every program (16 MiB,
`RontoLispCli`'s `WORKER_STACK_BYTES`, which `INTERPRETER_STACK_BYTES` must track) and
rethrows what that thread threw, so the leg measures the product's ceiling rather than the
harness's. A `StackOverflowError` from this leg is a real depth regression, not a
stack-size accident.

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
