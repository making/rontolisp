# The interpreter's depth ceiling is the CLI's, not the launcher's

**Invariant: `RontoLispCli.main` NEVER interprets on the thread the launcher called it on.**
It reads `--stack`, then runs the whole command line on a thread of its own
(`WORKER_STACK_BYTES`, 16 MiB) and waits for it. The interpreter's recursion depth is the
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
  and survives 8.
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

## What this does NOT cover

Compiled output runs its own `main` on the JVM's first thread -- `java -jar app.jar` is
sized by `-Xss` and the platform, not by anything above. Deliberate: compiled frames are a
fraction of an interpreter frame, and a compiled program's launcher has the knob. Measured
2026-09-19 (linux-x64, `.todo/899`): a Scheme tail call through a procedure value -- two
JVM frames per call, the caller and `_invoke_N` -- reaches 1,844 deep under `java Prog`
(1 MiB), 17,677 under `-Xss16m`, and 8,000,000 under `-Xss256m`, where the JIT's frames
take over after the first ~10k calls; the interpreter's 16 MiB worker holds 15,497 of the
same. So COLD, a compiled program is shallower than the interpreter; warm it is far deeper.
Compiled wasm has no ceiling for those calls at all (`.kb/wasm-tail-calls.md`).

## Pinning tests

`RontoLispCliTest#mainRunsTheProgramOnItsOwnStackNotTheLaunchersOne` calls `main` on a
1 MiB thread and, right after at the same depth, the CONTROL: `run` on the same thread, which
must overflow, so the test cannot pass on a stack it never needed. A control that fits
doubles the depth and pairs again. The pair used to be two tests sharing a depth searched
once per JVM; the depth was found cold and asserted on warm, and the control failed once under
parallel load (2026-09-17). In the same JVM after warm-up, 200 re-runs at the found depth
all overflowed locally -- the failure needs a JIT whose warm limit crosses the found depth,
which is why the pairing, not a margin, is the fix. `#theStackOptionIsReadOffTheRawArgumentsAndConsumed`
and `#theStackOptionRefusesASizeNoThreadCanBeGiven` pin the flag;
`#anUncaughtStackOverflowInAFileIsOneLineAndExitOne` the report and
`#aStackOverflowAtTheReplIsReportedAndTheSessionKeepsItsDefinitions` the recovery, in both
languages.

The in-process E2E interpreter leg mirrors the constant rather than the mechanism:
`AsdfLibraryE2eSupport`'s `INTERPRETER_STACK_BYTES` must track `WORKER_STACK_BYTES`, or the
leg measures JUnit's ceiling instead of the product's ([test-execution.md](test-execution.md)).
