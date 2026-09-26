# A program's depth ceiling is the product's, not the launcher's

**Invariant: `RontoLispCli.main` NEVER interprets on the thread the launcher called it on.**
It reads `--stack`, then runs the whole command line on a thread of its own
(`SizedThread.WORKER_STACK_BYTES`, 16 MiB) and waits for it. The interpreter's recursion depth is the
PROGRAM's, so a ceiling inherited from the platform is a different product on each one.

## The numbers (measured 2026-09-11, aarch64)

| stack | what the first thread gets |
| ----- | -------------------------- |
| macOS | ~8 MiB |
| linux-x64 | **1 MiB** |

- cl-mustache's spec suite through `asdf:load-system` renders ~**800 KiB** down: measured on
  the product BEFORE this change, where `-Xss` still sized the thread the program ran on,
  `-Xss768k` died and `-Xss832k` ran it (194 cases, `pass=158 fail=36`). An ordinary vendored
  library's own test suite therefore sat INSIDE Linux's default margin, and CI duly went red on
  `ClMustacheSpecE2eTest » StackOverflow` while every local box stayed green. The same jar
  today runs it at `-Xss512k` and at `--stack 1`: the launcher's stack no longer reaches the
  program (re-measured 2026-09-11, linux-x64).
- Interpreter frames cost roughly 1.5 KiB of Java stack per Lisp call: `(defun depth (n)
  (if (= n 0) 0 (+ 1 (depth (- n 1)))))` at 1500 overflows 1 MiB, at 4000 overflows 4 MiB
  and survives 8. **Since `.todo/912` (2026-09-19) a non-tail call is two Java frames
  where it was thirteen, and a tail call is none**: `depth` reaches 35,726 on the 16 MiB
  worker where it reached 10,435, and a tail-recursive chain has no ceiling at all
  (`.kb/interpreter-tail-calls.md`). The frame counts below are the pre-loop ones unless
  dated later; the mechanism -- the worker thread, `--stack`, the overflow report and the
  REPL's recovery -- is unchanged.
- **That cost is the JIT's, not the program's.** The deepest `depth` a 1 MiB thread holds,
  one JVM per row (2026-09-17, linux-x64, Oracle GraalVM 25.0.4, binary search per round):

  | JIT state | calls |
  | --------- | ----- |
  | `-XX:TieredStopAtLevel=1` (C1 frames only) | 337 |
  | `-Xint` | 373 |
  | first round, default | 511 |
  | warm, C2 (`-XX:-UseJVMCICompiler`) | 997-998 |
  | warm, Graal (default) | 1131-1132, dipping to 1055 |

  16 MiB holds 5986 under C1 only and 20430 warm. CI run 34637528638 held more than 1500 in
  1 MiB. So a depth that overflowed once can fit later in the same JVM, and a test must not
  reuse one (below).

## Mechanics

- `LaunchStack.of(args)` reads `--stack <MiB>` / `--stack=<MiB>` off the RAW arguments
  (the thread must exist before anything parses a command line on it) and CONSUMES the
  option, so no parser downstream -- `CliOptions`, `FormatCommand` -- knows it exists. The
  scan stops at the first bare `--`: everything after it is the interpreted program's own
  argument vector. Refuses anything but 1..65536 MiB.
- **`--stack` is the knob that replaces `-Xss`**: a `java -jar`'s `-Xss` sizes thread 0,
  which no longer runs the program, and the native binary never had one.
- Two arms in `main`. On the native binary on macOS
  (`ObjcInterop.mainThreadHandOverRequired()`, [objc.md](objc.md)) thread 0 parks in the
  AppKit run loop and never returns, so the worker ends the process with `System.exit`
  whatever the code. Everywhere else `joinLaunch` waits and carries the outcome out of
  main: `exit(code)` (0 returns normally, so an embedded caller is not killed), and what
  the worker THREW is rethrown on thread 0 -- an `Error` the CLI does not handle
  (`OutOfMemoryError`) keeps its trace and its failure exit exactly as when launch ran on
  thread 0. An interrupt aimed at thread 0 is remembered and re-asserted, never an excuse
  to stop waiting.
- **A `StackOverflowError` is not one of those.** `runReporting` turns it into ONE line,
  `error: stack overflow (--stack <MiB> raises the limit)`, exit 1 (the trace -- one call
  repeated thousands of times -- only under `RONTOLISP_DEBUG`). `RontoLispCli.run` still
  throws it, so an embedder keeps the `Error`.
- **The REPL survives one** (`ReplBuffer.eval`): it takes `LispEvaluator.controlState()`
  before the buffer and `restore()`s it after catching the overflow. The unwind does run
  every `finally`, but the deepest run with no stack left and can overflow again before
  restoring: without the restore, `(let ((*level* n)) (let ((*b* n)) (+ 1 (sink (+ n 1)))))`
  left `*level*` bound to 1 at the next prompt in one run of three (2026-09-17) -- the
  outermost value, because every pop above the lost one removed its neighbour's binding.
  The leak depends on where the overflow lands, so no test can force it. The state is what binding forms push and pop -- special
  binding depths, `handler-case` frames, `functionBodyDepth`, the load package stack and
  current package, the load directory and system stacks, the mute flag. Nothing a program
  ASSIGNED is rolled back, as after any error.
- Non-daemon threads a program started (the embedded HTTP server) still hold the JVM open
  after main returns: joining the worker changed nothing about that.

## Compiled JVM output runs on the same size of worker

Until 2026-09-19 a compiled `main` ran the program on the JVM's first thread, sized by `-Xss`
and the platform. Now the emitted `main` is a launcher (`codegen/jvm/JvmSizedMainBuilder`):
`new Thread(null, new Prog(), "main", Integer.getInteger("rontolisp.stack", 16) << 20)`,
start, join, rethrow on thread 0 what the worker threw. The old `main` body is emitted
unchanged as `private static _main$body(String[])`; the class itself is the `Runnable`
(`run()` -> `_main$run(this)`, which catches `Throwable` into the instance field
`_main$thrown`), so no second class file travels.

- **`-Drontolisp.stack=<MiB>` is the compiled twin of `--stack`.** `0` or less hands the size
  back to the JVM (`-Xss`); an unparsable value is the default (`Integer.getInteger`).
- What stays the same: the uncaught-condition report and the `Exception in thread "main"`
  echo (the SAME throwable is rethrown on thread 0; `JvmUncaughtHandler` lives in the body),
  exit codes, `System.exit`/`uiop:quit` from the program, argv, stdin, `java:` seeing a thread
  named `main`, a reflective `main` call observing the throw. An interrupt aimed at thread 0
  is remembered and re-asserted after the join, as in `joinLaunch`.
- A class that also carries the async runtime already has a `run()`: its head dispatches the
  launcher instance, told apart by its null `_asyncLatch` (`JvmAsyncRuntimeBuilder.build`'s
  `launcherRun`). `run` is a tree-shaker root whenever the launcher exists.
- **NOT emitted** -- and the output is byte-identical to before -- when there is no `main`
  (`--no-main`, `-o app.war`) and when the top level runs in `<clinit>` (any
  `rontolisp:jvm-export`, a war: the JVM initializes the class on the caller's thread before
  `main` could move anything). Checked 2026-09-19 by compiling a jvm-export class and a
  `--no-main` class with the jars before and after: identical.
- **A program that reaches `objc:`** (raw or through the spliced `appkit`/`metal`/`scene` layers)
  gets the launcher too, headed by the thread-0 hand-over a native image needs
  ([objc.md](objc.md), "AppKit belongs to thread 0"). Until 2026-09-27 it got none and kept the
  program on the calling thread; the macOS GUI check of the change is recorded there.

### The numbers (2026-09-19, linux-x64, Oracle GraalVM 25, before -> after)

- A tail call through a procedure value, largest passing depth under `java Prog`: Scheme
  `(define (g self n) (if (= n 0) 'done (self self (- n 1))))` **1,792 -> 16,201-16,207**; the
  CL `(funcall self self ..)` twin 1,775-1,816 -> 16,138-16,287 (`-Xss16m` before: 16,301).
  `-Drontolisp.stack=1` 1,855, `=256` passes the probe's 40,000,000 ceiling. The interpreter's
  worker held 15,497 of the Scheme calls until the loop in `eval` landed later the same day
  (`.todo/912`, [interpreter-tail-calls.md](interpreter-tail-calls.md)): a tail call has no
  ceiling there now, and its non-tail `depth` reaches 35,726 -- so on a tail call compiled
  output is the shallower one again, and on a non-tail recursion the two are level.
- Non-tail `(defun depth (n) (if (= n 0) 0 (+ 1 (depth (- n 1)))))`: 30,616 at the default,
  and under `-Xint` 9,072 at 1 MiB vs 160,309 at 16 MiB. C1 frames are the fattest:
  `-XX:TieredStopAtLevel=1` holds 2,230 in 1 MiB, the interpreter 9,072 -- which is why
  `JvmSizedMainTest` pins depth in a child JVM under `-Xint` (one frame size, 4x margin each
  side) instead of pairing a control as the interpreter's test must.
- Startup (`java -cp . Depth 10`, 100 interleaved runs): median 87.7 -> 88.6 ms, min 73.1 ->
  74.1 ms -- the extra thread costs about 1 ms, inside the noise. `bench-report` JVM column,
  best of 5 alternating twice: every benchmark within run-to-run noise.
- Size, every artifact with a `main`: +819-872 B of class (`(display "hello, world")` .scm
  1,661 -> 2,533, `(print ..)` 3,924 -> 4,743, `fib` 12,110 -> 12,929, an async program
  41,692 -> 42,379), +380-480 B of jar. `size-report` measures wasm only, so none of its
  numbers move.

## An embedder compiles on the same stack

`JvmSourceCompiler.compile` / `compileIfExported` (the Maven plugin's `LispSourceSet`) hand
the front end and the backend to a thread of `WORKER_STACK_BYTES` through `cli/SizedThread`
-- the same helper `joinLaunch` uses -- and wait: before 2026-09-24 they ran on the caller's
thread, 1 MiB for Maven's main thread on linux-x64. What crosses: the context class loader
and inheritable thread locals (the `Thread` constructor copies both); `SourceProvenance` and
`CompileWarnings` state is opened and closed inside the body; an `Error` or
`RuntimeException` is rethrown as itself. There is no WASM embedder seam; the playground runs
no threads. `compileProgram` (the CLI's `-o` path) stays on the CLI's own worker.

## Depth is NESTING, never a list's length

Every walk over program forms -- the front end's passes and splice detectors, the reader's
label patch and backquote expanders, the Scheme front end, both WASM backends' scans and
`WasmQuoteCompiler`, `LispEquality.equal` -- recurses on the car and LOOPS down the cdr. A
predicate or collector turns its tail call into a loop by hand, keeping every head check
the recursion applied to each sub-tail; a rewrite goes through `LispTrees.rebuildSpine`
(identity per cell as `LispCons.rebuilt`, the recursive walk's call order). Before
2026-09-24 ~216 walks recursed per element: through `JvmSourceCompiler` on 1 MiB a
`(progn ...)` of 1,400 forms compiled and 2,800 overflowed (`JsonLibrary$Walker.rewrite`);
on the CLI's 16 MiB a quoted list of 100,000 numbers overflowed even interpreted (60,000
ran) and a 400,000-form `progn` overflowed the compile path. After: 50,000 elements on
1 MiB, every backend (`WideListStackTest`).

A list whose tail closes into itself would now loop where it overflowed:
- A `#n=` label that does so in PROGRAM SOURCE is refused at the read
  (`SourceLanguage.read`, only when the text contains `#<digits>=`; `LispTrees.circularSpine`).
  A shared label and a cycle through a car stay legal (the latter still overflows).
- At run time (`eval`, `compile`, a macro expansion of a circular constant) `rebuildSpine`
  and `equal` run Brent's check and throw `LispTrees.CircularListException`, a program-error
  a `handler-case` catches -- the overflow before was not catchable.

What still recurses per element, by design: `LispEquality.equalpKey`/`hash` (capped at depth
64), the fdlibm tables, `JvmRuntimeBuilder`'s halving dispatch trees. Nesting still costs a
frame per level: `(g (g ... x))` 2,000 deep overflows `--stack 1` in the reader and 4,000
compiles on 16 MiB in ~6 s.

## Pinning tests

`WideListStackTest` runs the whole command line in process on a 1 MiB thread over
50,000-element lists (a `progn`, quoted numbers, quoted symbols on interpret / `.class` /
`.wasm` / `--component`; a backquote template interpreted and on `.wasm`; a 50,000-form defun body on
`--no-gc`), the source refusal, the run-time `CircularListException`, and
`anEmbedderCompilesOnTheCliStackNotItsOwn` (the CLI on 1 MiB must overflow on the nesting the
embedder then compiles from a 1 MiB caller). `LispTreesTest` pins `rebuildSpine`'s identity
and order and the cycle checks.

`RontoLispCliStreamsTest#mainRunsTheProgramOnItsOwnStackNotTheLaunchersOne` calls `main` on a
1 MiB thread and, right after at the same depth, the CONTROL: `run` on the same thread, which
must overflow, so the test cannot pass on a stack it never needed. A control that fits
doubles the depth and pairs again. The pair used to be two tests sharing a depth searched
once per JVM; the depth was found cold and asserted on warm, and the control failed once under
parallel load (2026-09-17). In the same JVM after warm-up, 200 re-runs at the found depth
all overflowed locally -- the failure needs a JIT whose warm limit crosses the found depth,
which is why the pairing, not a margin, is the fix. `RontoLispCliTest#theStackOptionIsReadOffTheRawArgumentsAndConsumed`
and `RontoLispCliTest#theStackOptionRefusesASizeNoThreadCanBeGiven` pin the flag;
`RontoLispCliStreamsTest#anUncaughtStackOverflowInAFileIsOneLineAndExitOne` the report and
`#aStackOverflowAtTheReplIsReportedAndTheSessionKeepsItsDefinitions` the recovery, in both
languages.

`JvmSizedMainTest` pins the compiled launcher: depth under `-Xint` in a child JVM at the
default and at `-Drontolisp.stack=1`/`=0`, the one-line report and exit 1 from thread 0, the
thread's name, the ABSENCE of the launcher from a jvm-export class, and an `objc:`/`appkit:`
class's hand-over (macOS: simulated on the JVM under `-XstartOnFirstThread`).

The in-process test legs mirror the constant rather than the mechanism:
`testsupport/CliStack.BYTES` must track `WORKER_STACK_BYTES` (`RontoLispCliTest` pins it), or
a leg measures JUnit's ceiling instead of the product's ([test-execution.md](test-execution.md)).
