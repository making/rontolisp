# Threads (`rontolisp:make-thread` family) and the `bt2` shim package

`rontolisp:make-thread` (fn + optional bindings alist + optional name), `join-thread`, `threadp`,
`thread-alive-p`, `destroy-thread`, `current-thread`, internal `%thread-name` — interpreter and
JVM only. Driving
consumers: clack `src/handler.lisp` (`:use-thread t`); Scheme `parallel-execute`
(`.kb/scheme-frontend.md`); a Java program's `Thread` on the interpreter and the JVM
(`.kb/java-frontend.md`, "Threads": wasm runs those on one stack and calls none of this).
**Invariant: a thread handle is OPAQUE** (`.kb/mutexes.md`); only `threadp` and the join
round-trip are portable.

## Backends
- Interpreter: `LispThread` record (thread, result, name) from `AsyncRuntime.spawnThread` — the ONLY
  LispEvaluator-reachable class touching threads, so the browser playground substitutes it
  wholesale (`Target_AsyncRuntime.spawnThread` runs the body synchronously, and hands back the current
  thread: `join-thread` skips the `Thread.join`, which hung the page until 2026-11-05). A body
  run so cannot wait for another thread: a `condition-wait` with no limit is refused there
  ([mutexes.md](mutexes.md)), a Java program that starts threads is run compiled instead and
  its session starts none (`LispEvaluator.refuseThreads`; `.kb/java-frontend.md`, "In the
  browser").
- JVM: `{TMARKER, Thread, FutureTask, name}` marker-headed `Object[]`; `JvmThreadRuntimeBuilder`'s
  class `implements Callable`, `_thread_spawn` builds the handle on an UNSTARTED virtual thread,
  stores it in the runner's `_threadHandle`, then starts it. Threads storing into one general
  array or through one string view: [adjustable-arrays.md](adjustable-arrays.md), "Where another
  thread can run the program's Lisp".
- WASM (both): no handle can exist; the shim's `#+rontolisp-wasm` defuns SIGNAL at CALL time —
  never inline, which would turn a compile-time error into a silently sequential program.
  `threadp` answers nil, `bt:*supports-threads-p*` nil (WASM clackup runs `:use-thread nil`), the
  direct `rontolisp:make-thread` spelling is an undefined function, a call and a `#'` alike
  (`BuiltinFunctionWrappers.WASM_UNSUPPORTED`; the interpreter and the JVM have the values,
  [lisp2-namespaces.md](lisp2-namespaces.md)); `--no-gc` rejects the names.

## A value that is no thread
`join-thread`/`thread-alive-p`/`destroy-thread` of a non-thread: the operator's `type-error` expecting
`(SATISFIES RONTOLISP:THREADP)` on the interpreter (`requireThread`) and the JVM (`emitRequireThread`,
`_threadp` first); the wasm bt2 `thread-alive-p` signals the same condition and text by hand. A bindings
alist that is not a proper list of `(symbol . value)` conses is `make-thread`'s `simple-error` BEFORE a
thread starts on both (the JVM's `emitRequireBindings`; it used to spawn and fail at the join). A
non-function first argument still spawns on both and fails at the join. Pinned by `HandleOperandFixture`
([error-handling.md](error-handling.md), "A wrong-type argument names its operator").

## Bindings, join, feature
- The `(symbol . VALUE)` alist binds in the NEW thread only; a spawned thread inherits NO bindings
  (`.kb/dynamic-special-variables.md`) and they die with it. Interpreter: `DynamicBindings.push`,
  names need not be special (`progvUsed`). JVM: names arrive as runtime STRINGS, resolved via
  `_dtl(name)` -> `_d$` ThreadLocal -> `_dbind`; `JvmLispCompiler` forces
  `*STANDARD-OUTPUT*`/`*STANDARD-INPUT*`/`*ERROR-OUTPUT*` into `specialVars` (clack binds them
  through the alist, invisible to `SpecialVarCollector`; `.kb/standard-output-redirect.md`) and
  ALL specials into `boundSpecialVars`, which the thread primitives make thread-scoped (the
  program's specials are never bound shallow, `.kb/dynamic-special-variables.md` "One thread").
  A name that is one of the program's own plain (non-special) globals is a clear runtime error,
  not a silent miss; any other name (none of the program's variables, or a CL standard variable
  it never binds -- `bt:*standard-io-bindings*`'s reader variables) binds nothing, since no
  compiled read could see it (`_dtl` answers null). `_dtl` is a chain of methods of 2,000
  comparisons each (`_dtl`, `_dtl$1` ..), each handing what it does not find to the next: one
  is 14 bytes of code, and the Java corpus's program, whose class objects are specials, passed
  the 64 KB a method may have (72,588 B, 2026-11-23, k89). A closure built inside
  a binding extent captures no special: run on the new thread it reads that thread's binding,
  else the global.
- `join-thread` RE-SIGNALS the thread's error so `handler-case` dispatches by condition type. On
  the JVM the condition cannot ride `_condTl` across threads, so `call()` completes the FutureTask
  NORMALLY with `{EMARKER, throwable, condition}` and `_thread_join` records the condition under
  the throwable on the joining thread before rethrowing it (the `_await` pattern). Join
  `Thread.join`s after the value settles, so `thread-alive-p` answers nil deterministically.
  `destroy-thread` is `Thread.interrupt` — a request, not a kill.
- `:thread-support` is in `Features.INTERPRETER`/`Features.JVM`, not WASM; declared statically
  because an upstream `.asd` push reaches only that file's own conditionals
  (`.kb/reader-features.md`). Consumer: `clack:clackup` (`.kb/clack.md`). Pinned by ci-spec
  `reader-features-variable` (feature count 4 vs 3).

## What a thread keeps of its own
**The handler and restart stacks** (`%handler-clusters%`, `%restart-clusters%`), interpreter and JVM: every thread
running the program's code has its own, starting empty, whatever started it. The rule is the variable's, not an
entry's, so a `make-thread` body, a served request (all its transports) and a thread Java or a host calls in on
need no site: **a store never reaches the global**, a thread with no binding binds it there
(`LispEvaluator.assignVariable`, `JvmSetqCompiler.emitGlobalStore`), and every read falls back on the global's nil.
On the JVM the stacks get their `_d$` ThreadLocal wherever `lispOnOtherThreads` holds (`THREAD_OWN_STACKS`); a
one-thread program keeps the shallow `_g$`. They were one global: a thread leaving its `handler-bind` restored what
it had saved over what another thread pushed since, and that thread's condition found no handler (sento's `tell`
across threads, fiveam's `is` on the main one: `Unhandled condition`, measured 2026-11-05 on both). An async body
starts from its caller's instead ([async-await.md](async-await.md), "The eager part is a call"). An editor's
request binds them as well (`CommonLispSwank.serve`), to keep them between its inputs. Pinned, deterministically
(the other thread pushes between a thread's push and its restore), by `aThreadsHandlersAreItsOwn` in `ThreadTest`
and `JvmThreadTest` (a `make-thread`, a thread Java starts, an async body) and `aRequestsHandlersAreItsOwn` in
`HttpHandlerTest` and `HttpHandlerJvmTest`.

The rest of this section is the interpreter's.

**The current package** (`LispEvaluator.withOwnPackage`, `PackageResolver.bindThreadPackage`): a `make-thread`
body, an async body and a served request run under one starting from their spawner's. A macro's first
expansion moves the current package to where the body was read, by save, set and restore; through the one
shared state that move reached what the program read meanwhile (2026-11-05: `(print (future:fresult *f*))`
read `SENTO.ACTOR::*F*`), and two overlapping moves restored the other's package for good. The cost: an
`in-package` on the main thread does not reach a running thread, where SBCL's global value would. Making only
the swaps per thread keeps that, at nine sites, and leaves the main thread's own swaps visible to the
others. Pinned by `ThreadTest#aMacroExpandingOnAThreadLeavesItsPackageToThatThread`.

**The mute around a compiler macro's expansion** (`MutablePrintStream`): a thread local. As one flag it lost
what another thread printed meanwhile, and two overlapping expansions left it set: with two lparallel workers
nothing the program printed afterwards appeared, exit status 0. Pinned by
`ThreadTest#aCompilerMacroExpandingOnAThreadMutesOnlyThatThread`.

## A program's end ends its threads (interpreter, in process; 2026-11-21, k83)
A process's end ends every thread of the program (each interpreter thread is a virtual one, a daemon). A
runner holding the evaluator for one whole program in process has no process to end, so the evaluator keeps
the threads the program started (`make-thread`, an async body: `AsyncRuntime`'s `starting` hook records one
before it starts, its body's end forgets it) and `LispEvaluator.stopThreads` ends them: each stops at its
next call or loop step (`interrupt`; a sleep, a wait or a `mutex-acquire` wakes) by `LispExitSignal`,
so it runs no cleanup, as a process's end runs none. No thread starts afterwards (a `simple-error`). It
answers the threads, which a caller may join.
- **Called by** `RontoLispCli.interpret` when the program did not run off its end (a quit, an uncaught
  condition: `main` ends the process) and at a Java entry's end, whose lowering has waited for its threads
  that are no daemons and its servers (`%java-thread-finish`, `%java-http-wait`); `JavaSpecE2eTest`'s
  interpreter leg always, joining them. NOT at a Lisp program's normal end: a thread that is no daemon (a
  server's) may hold its process, serving on a thread the program started (clack's `:use-thread`). Not by a
  REPL buffer, a session's input or `PlaygroundRepl` (a buffer's thread outlives it; the browser starts none).
- **Left**: a thread stopped between its own `mutex-acquire` and `mutex-release` leaves the mutex held, and a
  thread retaking it after a wait (`RontoConditionVariable.await`) stays blocked, using no CPU.
- Found by k81: `java-spec.yaml`'s `threads-wait-for-one-another-again-and-again` ends with a daemon
  `while (true) counter.incrementAndGet();`, which spun in the surefire fork until it exited (100 s of CPU in
  103 s); `threads-share-concurrent-collections-and-completable-futures` left parked pool workers. Pinned by
  that leg: both legs ran into the 300 s limit before the stop.

## bt2 shim
`bordeaux-threads.lisp` (ShimLibraries) provides BOTH namespaces of the one built-in
`bordeaux-threads` system; `BT2` canonical, nickname `BORDEAUX-THREADS-2`. Cross-package IMPORT
redirects make one definition serve both spellings, so `bt2:with-lock-held` hits the existing
`LispMacroExpander` qualified-name dispatch with NO new dispatcher entries.
`bt2:make-thread`'s `:initial-bindings` maps onto the primitive's alist via
`bt2::resolve-binding-value`, which accepts `quote` forms and self-evaluating values and SIGNALS
otherwise; a library passing a variable-reference form would need eval-in-the-new-thread
(`.kb/eval-runtime.md`).

**bt2's atomic counter** (`make-atomic-integer`, `atomic-integer-value` and its setf, `-incf`, `-decf`,
`-compare-and-swap`; 2026-11-07, petalisp's worker barrier): a structure over a value and a mutex of its
own, every operation under it -- upstream's arm for an implementation without a compare-and-swap, so
atomic on the interpreter and the JVM and a lock taken for nothing on wasm. The value is a 64-bit word
stored wrapped, and incf/decf answer the old value plus or minus the delta unwrapped, as upstream's SBCL
arm does (`(atomic-integer-decf a)` of 0 answers -1 and leaves 18446744073709551615). Pinned by ci-spec
`bt2-thread-api-portable-surface`.

**bt2 exports all of bordeaux-threads 0.9.4's names** (2026-11-08; until then a file naming `bt2:lockp`,
`bt2:abnormal-exit` ... stopped at read time). Behind the ones a backend cannot do is bt2's own
`not-implemented`: the shim's `bt2::not-supported` (a `simple-error` too, its report the reason) is what
`interrupt-thread` and its `signal-`/`warn-`/`error-in-thread`, `with-timeout` and the WASM thread entry
points signal, so a handler for upstream's condition catches them. `join-thread` signals `abnormal-exit`
over the error that ended the thread (the primitive re-signals it; v1's spelling is the same symbol, and
upstream's v1 on SBCL signals `join-thread-error` there, not the thread's error either). A lock keeps its
name (both spellings), `lock-native-lock` answers the mutex handle and `thread-native-thread` the thread.
`native-lock-p` / `native-recursive-lock-p` (and the two types over them) signal `not-implemented`: a
mutex handle is opaque by design, an integer on the interpreter, so no predicate can tell one -- a new
handle type on four backends for a predicate no known library calls is not worth it. The condition
variable's structure moved to `bt2:condition-variable`, its upstream home (v1 never exported it). Pinned by
ci-spec `bt2-thread-api-portable-surface` and `ThreadTest`/`JvmThreadTest`
`bt2ShimSpawnsThroughThePrimitivesWithInitialBindings`.

`current-thread` is **EQ-stable per thread** — what dbi's per-thread connection cache keys on
(`cache/thread.lisp`'s `steal-cache-table`). Interpreter `AsyncRuntime.currentThreadHandle()`
(ThreadLocal-cached, future never completed — self-join blocks forever); JVM `_thread_current`
caching in `_curThreadTl` (null task slot — self-join NPEs). Works for ANY thread; in a spawned
body it IS the spawner's handle (spawnThread / `call()` seed the ThreadLocal before the body
runs), which is how `bt:thread-name` of `(bt:current-thread)` finds the name (log4cl).

`bt:all-threads` is a shim registry: `bt2:make-thread` spawns and registers under one mutex,
dropping dead entries; the answer is the thread that loaded the system plus the live entries.
Threads spawned otherwise (a served request, the raw primitive) are absent.

## Tests
`ThreadTest`, `JvmThreadTest` (both run `HandleOperandFixture`),
`WasmLispCompilerIntegrationTest#bt2ThreadEntryPointsSignalWhileThreadpAnswersNil`, ci-spec
`bt2-thread-api-portable-surface` (spawn/join excluded — output cannot be identical across
backends).
