# async/await: async-defun, await, futures and asynchronous streams

## Surface
- `rontolisp:async-defun` / `rontolisp:async-lambda`, plus the `rontolisp:async` WRAPPER macro
  (`(async (defun ...))`, `(async (lambda ...))`, else an error). A pure frontend rewrite
  (`LispMacroExpander.expandAsync`, `rewriteAsyncSugar`) that must run before EVERY consumer of the
  canonical forms. `rontolisp:async` joins NO introspection listing.
- `rontolisp:await` is a SPECIAL FORM. `rontolisp:wait-for` is ms -> a future settling to nil, NOT
  `sleep`.

## Cross-backend contract
- **Eager start**: the body runs immediately until its first await of a future the program does not
  KNOW settled, so output before the first suspension is identically ordered everywhere ("Where the
  eager part ends"). Await of a known-settled future or a non-future never suspends; nested futures
  flatten; an errored future re-signals AT AWAIT.
- **Values**: `await` answers every value the body answered ("Multiple values").
- **await placement is lexical**: legal in async bodies and at top level, illegal in any plain
  defun/lambda even nested inside an async body (the JavaScript rule).
  `am.ik.rontolisp.LispAsync.checkTopLevel` (compilers) / memoized `checkAwaitPlacement`
  (interpreter), which PRE-APPROVES the `%async-run` thunk lambdas the lowering synthesizes.
- **Lowering**: `(async-defun name (ll) body...)` -> `(defun name (ll) (rontolisp::%async-run (lambda
  () body...)))`. `%async-run` is the ONE backend primitive; the lambda list stays on the outer defun.

## Per-backend implementation
- **Interpreter** `eval/AsyncRuntime.run` = virtual thread + eager-start handoff latch, the ONLY
  LispEvaluator-reachable thread site; the playground substitutes it
  (`src/web/java/.../Target_AsyncRuntime.java`).
- **JVM** `JvmAsyncRuntimeBuilder`: future = `runtime/RontoFuture`, a `CompletableFuture` that knows
  whether it is known settled; `_async_run` spawns `Thread.ofVirtual()`. **An error cannot ride the
  `_condTl` ThreadLocal across threads**: `run()` completes NORMALLY with `{EMARKER, throwable,
  condition}` (plus the trace when the class carries location lines,
  [error-handling.md](error-handling.md)) and `_await` records the condition under the throwable on
  the awaiting thread before rethrowing -- that is what makes handler-case dispatch across the await.
  A plain failure records nothing, so a condition already on its way out stays its own.
- **Preview-1 wasm-GC, a module naming `rontolisp::%future-new`**: the component's state machines,
  unchanged. A program that makes futures of its own (the threads of a Java program, `java.lisp`) has
  bodies that suspend whatever the host is, so `WasmLispCompiler` turns `asyncMode` on for it
  (`LispNames.FUTURE_NEW_QUALIFIED`): with no host future the scheduler members are stubs, an await
  of a pending future suspends the frame, `%future-settle` resumes the waiters on the settler's
  stack, and a pending await at top level ends the program as on a component. No host timer:
  `wait-for` stays the compile error, and whoever settles the futures passes the time itself. The
  one host future there is a `--native` fetch's deferred one (`.kb/fetch-http.md`, "--native"). A
  resumable call that does not suspend costs 88 ns there as on the component (33 ns degenerate).
  Pinned by `WasmLispCompilerIntegrationTest#componentAsyncLambdaCapturesAndArgumentHoistAcrossSuspension`
  and the wasm legs of the Java corpus's thread cases.
- **Preview-1 wasm-GC, every other module**: degenerate synchronous. `WasmAsyncRunCompiler` wraps
  the value in a settled `TYPE_P1_FUTURE {mut i32 kind, mut value}` (the kind field exists so the
  shape does not canonicalize into `TYPE_CELL`). The one UNSETTLED kind is 3,
  `rontolisp::%future-deferred`'s, a thunk every await runs; the await carries that arm only in a
  module naming the primitive. **An async body's ERROR signals at the await, as everywhere else, in
  an EH-mode module**: `%async-run` catches `$lisp-cond` and settles a FAILED future (kind 5) over
  the payload, which every await throws again (the same payload, so `--report-locations`' frames
  survive, [error-handling.md](error-handling.md)). Outside EH mode nothing can catch a condition, so
  the body still traps at the call (the one observable difference left: output between call and
  await). **`--no-gc`** rejects the whole async surface by name, `%stream-new` included.

## Where the eager part ends
**Invariant: where an async body's eager part ends depends on no thread's timing.** It ends at the
first await of a future the program does not know settled. Known settled: settled before the call
that made it returned (a body that finished without suspending, `stream-write`'s answer, a pull
stream's read), or resolved by an await since. Anything else -- a timer, a fetch, a push stream's
read, a body that suspended -- ends it even when it has settled by then.
- Interpreter: `LispFuture.knownSettled`, set for a body completing with its handoff pending, by
  `ofSettled`, and by `joinFuture` after every join.
- JVM: `runtime/RontoFuture` (travels with every async program, [jvm-export.md](jvm-export.md));
  `_await` asks `knownSettled` and joins through `RontoFuture.join`, which marks. A Java
  `CompletableFuture` is never known.
- Component: a host future settles only on a scheduler turn, so settled is known there, but for a
  future the scheduler settled while the program awaited ANOTHER. The component then goes on where
  the threaded backends hand off -- one of the orders they allow. A host call answered at once
  (status RETURNED: wasmtime 49's `wait-for` of 0 ns) still stays pending on the next-turn list
  (`Sched.nextTurnGlobal`), which `_sched_loop` settles oldest first before waiting on the host and
  `_task_finish` before answering WAIT -- else `(await (wait-for 0))` never yields and a body polling
  a variable with it hangs. Limit: a turn drains the list without asking the host (no
  `waitable-set.poll` import), so a body polling with `(wait-for 0)` for what only a HOST event
  settles never lets that event in; poll with `(wait-for 1)` there.
- An await on another thread marks the future when it returns, so a parallel program's handoff can
  follow its own race; a one-thread program's cannot.

Rejected: ending at the first await of a future not yet DONE (a timer settles on the JDK's delayer
thread, so on a loaded box `(await (wait-for 1))` found it done, and a body that then waited for its
caller hung both -- `ThreadTest#aThreadsHandlersAreItsOwn` held a full test run for hours); ending at
EVERY await (ECMAScript: what follows a settled await would race the caller on the threaded
backends); marking only what arrived settled (a body re-awaiting a timer its caller had awaited
raced the caller); settling a 0 ms timer at its making (a program would lose its one yield, and a
timer would again be known settled by how fast it fired).

Pinned by `aThreadsHandlersAreItsOwn` in `ThreadTest`/`JvmThreadTest` (it polls `%future-settled-p`
until the timer fires, so nothing depends on how fast), the last two cases of
`OwnHandlerStacksPrograms.SUSPENDING`, and ci-spec `an-async-bodys-eager-part-is-a-call`'s
`ci-eager-body-exit` (all four).

## The eager part is a call
**Invariant: before its first suspension an async body sees what a call sees, on all four
backends.** It starts from its caller's handler and restart stacks and armed `handler-case`s, so a
`handler-bind` around the call runs for what the body signals and `find-restart` finds a restart
around it. A handler's non-local exit, and the body's own to a block outside it, leaves the call.
Only a condition no handler took stays the future's, for the await to signal: a body's `error` under
a `handler-case` around the CALL alone still reaches the `handler-case` around the await (nothing
transfers across a call that has to answer a future). Preview 1 and the component run the eager part
on the caller's stack anyway, so "no" would have meant emptying the stacks there; a test framework's
`handler-bind` around a call keeps working.

**After the first suspension the body keeps what it started from and owns what it pushes**: a
handler around the call stays visible to it even past that `handler-bind`'s end (the one thing a
snapshot cannot tell, on all three suspending backends alike), and nothing it pushes reaches its
caller or another body.
- Interpreter: `runAsync` / `withCallersHandlers` ([threads.md](threads.md), "What a thread keeps of
  its own"). JVM: `_async_run` hands `run()` `_asyncState`.
- Component: **a suspended frame keeps them** in the three spill slots below `SPILL_BASE`
  (`WasmFutureRuntimeBuilder.HandlerState`, only with a handler stack); `_wake_list` swaps them
  around every resume. Without the swap a body's `handler-bind` left its cluster in the one global
  while suspended and restored over what the caller had pushed since. The specials' refusal to bind
  across an await (`.kb/dynamic-special-variables.md`) stands.

**An exit crosses the future.** A resumed or threaded body has no caller's frames below it, so the
exit waits at the await: the interpreter's `joinFuture` rethrows a control transfer as itself, the
JVM's payload carries the throwable's `_nleTl` entry and `_await` pushes it on the awaiter's channel
(a landing reads the channel of the thread it lands on), the component settles the frame's future in
`STATE_EXITED` and `_future_poll` throws `$block-exit` again. An exit out of the eager part goes on
from the call (`AsyncRuntime.run` and `_async_run` are still waiting, and rethrow it). A component's
`async-lambda` is a function boundary to `CrossLambdaExitLowering` too.

Pinned by ci-spec `an-async-bodys-eager-part-is-a-call` (all four) and
`OwnHandlerStacksPrograms.SUSPENDING` (`anAsyncBodysHandlersAndExitsAcrossASuspension` in
`AsyncEvalTest` and `JvmAsyncCompilerTest`,
`WasmLispCompilerIntegrationTest#componentAsyncBodysHandlersAndExitsAcrossASuspension`). **A program
pinning this orders its bodies by a turn its caller hands out, not by a timer**: on the interpreter
and the JVM a resumed body runs in parallel with its caller (timer-ordered holders failed CI and 24
of 1500 loaded interpreter runs; none of 1500 on turns).

## Multiple values
**Invariant: an async body's values reach its awaiter the way a function call's do.** The future
settles with the body's full value list, captured where the body completes, on the thread (or in the
resume) that ran it; `await` answers the primary and publishes the extras to `%mv-spill`
(`.kb/multiple-values.md`). The last future of a flattened chain decides; a non-future operand, and
every future no async body made (fetch, wait-for, a stream read, a subtask), is one value. A program
relying on `await` answering ONE value -- reading the body's publish out of a shared channel -- gets
a silent NIL far from the cause. Pinned by `AwaitValuesMatrix` on all four backends
(`AsyncEvalTest`/`JvmAsyncCompilerTest`/`WasmLispCompilerIntegrationTest` `*AwaitAnswersEvery*`, the
`await-answers-every-value-of-the-async-body` ci-spec case), its suspending half (not on Preview 1)
and `CONCURRENT_PROGRAM`, sixty bodies consuming values in parallel.
- The capture reads the channel right after the body's tail, which the tail settle made exact. It is
  race-free only because the channel is one per thread (`.kb/multiple-values.md`, "One register per
  thread").
- JVM: `run()` completes with `{VMARKER, primary, extras}` when the channel is non-nil. Preview 1: a
  `KIND_VALUES` (4) future over `(primary . extras)`. `--component`: `TYPE_FUTURE`'s fourth field;
  the ENTRY clears the channel on all three exits (it answers one value, the future), and
  `_future_poll` publishes each fulfilled hop's extras.
- Gated on the spill global: a program with no multiple-value consumer is byte-identical on the three
  compile targets.

## `--component` (asyncMode)
- Async bodies compile as ENTRY+RESUME state machines over first-class `TYPE_FUTURE`s
  (`WasmAsyncEmit`); asyncMode FORCES EH mode. A suspend site spills only what can still be read after
  it, and a long top level continues in resumes of its own (`.kb/wasm-function-body-size.md`,
  "Keeping the top level bounded").
- **Trap**: an async-defun's rewritten plain defun is EXCLUDED from the fusion-inlinable set even
  when a one-form body qualifies textually (`.kb/wasm-int-fusion.md`).
- **Trap**: an `async-lambda` binds its parameters as a `lambda` does in
  `FreeVarAnalyzer.findFreeVars` / `findCapturedVars`; read as a call, a parameter of a nested
  `async-lambda` becomes a free variable of the body around it (`Cannot find variable for closure`).
- **A resume body declares the i64 and typed scratch locals its body names**: `WasmAsyncEmit`
  assembles its entry through `WasmLispCompiler.assembleEntry`, the same assembly any body gets --
  else a placeholder stays `local 0` (the parameter) and `WasmRefTypeFolder` fails (`(random 1.0)`'s
  draw parks the generator in an i64 scratch). What is LONG-LIVED in an i64 local (`dotimes`, fixnum
  versions, an unboxed `let`) still declines a resume body (`ctx.asyncResume != null`): a suspension
  spills only the eq locals. Pinned by java-spec `threads-wait-are-notified-and-interrupted`.
- **Trap**: a region's landing pad must not restore the resume target `$rt`
  (`.kb/wasm-landing-pad-refresh.md`, "`$rt`").
- An `async func` wit-import member returns a pending `TYPE_FUTURE` via `rontolisp::%subtask-future`;
  events dispatch through the shared core `_sched_dispatch` under TWO drivers.
- **Blocking driver `_sched_loop`** (`waitable-set.wait`, wasmCloud-legal) at `_start` and at a
  non-serve wasm-export wrapper whose target answered a pending future
  (`.kb/wasm-export-no-wasi.md`).
- **Callback driver** at serve's `handle`: frames carry their task record as OWNER; `_task_suspend`
  stores the task id in **context slot 0** (wasmtime 46 validates the immediate to 0, so the
  waitable-set handle rides the record) and returns `WAIT | (set << 4)`; the host feeds events to the
  core-exported `_async_cb`.
- **Cross-task wakeup is a per-task DOORBELL** (an intra-component `stream<u64>` with a standing
  pending read): `_wake_list` resumes a waiter directly only when its frame's owner IS the current
  task (or null), else appends to the owner's ready list and rings the doorbell on the
  empty-to-nonempty transition, the callback re-arming the read BEFORE draining. Tasks are
  cooperative and single-threaded; a completed task's doorbell ends and waitable-set are LEAKED.
- Component streams: a read of an in-flight chunk is a PENDING future on the scheduler registry,
  settled by `_sched_loop`'s EVENT_STREAM_READ. A SECOND read before the first settles is a host
  trap; interpreter/JVM queue. Guest `make-stream`/`stream-write` stay compile errors.
- **A read in flight can be WITHDRAWN** (a stream alias's `-cancel-read`,
  `WasmComponentImportCompiler.emitStreamCancelRead`): it unlinks the registry entry, takes the
  handle out of the task waitable-set and THEN calls the SYNCHRONOUS `stream.cancel-read` -- joined,
  the built-in traps `waitable cannot be used synchronously while added to a waitable set` (wasmtime
  49). Outside asyncMode a read parks until done, so the wrapper calls nothing and the core module
  keeps no import of it. sockets.lisp's deadline, `listen` probe and close rest on it
  ([tcp-sockets.md](tcp-sockets.md), "Read deadlines").
- **A DROPPED read completion is LATCHED per readable handle.** DROPPED beats COMPLETED in `(n << 4)
  | code`, so one read can deliver its last items AND the end (`Dropped(n)`, n > 0); the host then
  traps any further `stream.read`. Every completion path pushes the handle onto one global list on
  DROPPED, and the read wrapper answers a listed handle nil without touching it; `drop-readable`
  unlinks it (handle numbers are reused). wasmtime 49 produces `Dropped(n)` intra-component (an
  undelivered `Completed(n)` merges with the writer's drop). Pinned by
  `WasmLispCompilerIntegrationTest.componentStreamRead*`.

## `wait-for` and the combinators
Interpreter and JVM: `completeOnTimeout(nil, ms, MILLISECONDS)` (JDK shared delayer, no new thread
site). `--component`: the `wait.lisp` shim over wit-imported `wasi:clocks/monotonic-clock@0.3.0`
`wait-for`, an async import call, so timers overlap in delay order; the interface is in the fixed
import block (`FIXED_BLOCK_IFACES`). Preview 1 keeps the compile error; `--no-gc` the rejection.

`then` / `then*` / `catch` / `finally` are prelude `defun`s, one definition for every backend, each
`funcall`ing an `async-lambda` over `await` + `handler-case`/`unwind-protect`, so the WASM EH-mode
gate flips automatically. Their names go at the FRONT of `NoGcWasmCompiler`'s list so the diagnostic
points at `rontolisp:then`. Preview 1 supports only the success half. A non-future first argument is
the operator's `type-error` expecting `(SATISFIES RONTOLISP:FUTUREP)` on all four (`OperandTypes`
rows; a program spells them `RONTOLISP:`-qualified, so CL's `catch` form never names the `CATCH`
row). Pinned by `AsyncCombinatorOperandFixture` (`combinatorsRejectNonFutureFirstArgument` /
`futureCombinatorsSignalATypeErrorOverANonFuture`, ci-spec
`future-combinators-signal-a-type-error-over-a-non-future`).

## `%stream-new`, the four-backend pull stream
`rontolisp::%stream-new` (internal) is the ONE producer of a first-class PULL stream: read thunk,
close thunk, drained flag. Nothing about it is WASI, which is why one primitive serves both WASM
tiers.
- **The thunk's answer is resolved AT THE READ, before the end-of-stream test**, on every tier. A
  future wrapping nil is not nil -- without the resolve such a thunk could never report EOF.
- **A pull stream has no write end**: `stream-write` refuses with "the stream has no write end", not
  "the stream is closed". The JVM's `_drain_body` reads through `_stream_read` + `_await`, so ONE
  drain serves both modes.
- **Gated on `%stream-new` appearing** (`WasmLispCompiler.usesP1Streams`): the type and functions
  take the slots the async block would have used, which cannot coexist, so no index moves and a
  stream-free module is byte-identical. A module that can hold NO stream keeps the call-time error
  stub, but `streamp` there is the CONSTANT NIL.

## A value that is no stream
**Invariant: `stream-read`, `stream-close` and `stream-write` of a value that is no stream signal the
operator's `type-error` expecting `(SATISFIES RONTOLISP:STREAMP)` on all four backends (wasm-GC: in
EH mode; outside it the helpers' cast traps)** -- in call position, inside an async body (at the
await) and under `read-all`. Where each backend checks: [error-handling.md](error-handling.md), "A
wrong-type argument names its operator". Pinned by `AsyncStreamOperandFixture`
(`streamVerbsSignalATypeErrorOverANonStream`, ci-spec
`stream-verbs-signal-a-type-error-over-a-non-stream`).

## Function values
**Invariant: `#'rontolisp:streamp`, `futurep`, `stream-read` and `stream-close` are function values
on all four backends, `make-stream` and `stream-write` on the interpreter and the JVM** (the wasm
backends refuse the program at the `#'` as at a call). The compiled values are reference-gated
`NATIVE_VALUE_FUNCTIONS` entries derived from the `NativeCallShapes` rows
([lisp2-namespaces.md](lisp2-namespaces.md), "A native built-in's function value"); the body is the
call position, and the async-runtime gates already scan for the name the `#'` spells. Pinned by
`AsyncStreamOperandFixture.VALUE_PROGRAM` / `NO_STREAM_VALUE_PROGRAM` / `WRITE_VALUE_PROGRAM`
(`asyncPredicatesAndStreamVerbsAreFunctionValues`, ci-spec
`async-predicates-and-stream-verbs-are-function-values`).

A `cast failure` trap on both wasm legs in an async body is not necessarily an async-lowering bug:
the measured case was a program redefining an internal `clojure.lisp` helper name, so the deref handed
a condition to `stream-read`, which trapped on wasm where the interpreter and the JVM signalled.

## `read-all` is prelude Lisp
An `async-defun` in `LispPreludeLibrary` -- **compiler tests that use it must mirror the CLI's
`LispPreludeLibrary.process` pre-pass**. A `stringp` arm before the drain loop PASSES A STRING
THROUGH. STRING chunks are concatenated through a string output stream (not pairwise -- quadratic);
OCTET chunks are joined and decoded by `rontolisp::%octets-to-string`, the LENIENT UTF-8 decoder (a
byte leading no valid sequence, or a truncated one, is its own character). Mixing kinds is an error.
`%octets-to-string` is prelude Lisp on the compile paths and a native `Environment` mirror on the
interpreter; `LispPreludeLibraryTest` pins the two arm for arm.

**The lenient loop is the FALLBACK, for a general array only: `%octets-to-string-packed` runs
first**, NATIVE on every backend, for every packed `(unsigned-byte 8)` vector (`nil` otherwise). The
interpreter and JVM run ONE lenient transcode, counted first so the result is built once at its size
-- on valid UTF-8 every lenient arm answers what a strict decoder answers, so no strict pass stands
in front (pinned by `LispPreludeLibraryTest.theDecodeOfValidUtf8IsThePlatformsStrictDecode`; a strict
decode first plus a fallback went through five body-sized intermediates, `.kb/fetch-http.md`,
"Throughput"). Both WASM GC tiers validate STRICTLY first and `array.copy`, transcoding only the rest
(`WasmStringRuntimeBuilder.buildIvUtf8StrBody`). Pinned against the loop by the three
`octetsDecodeNativelyWhetherOrNotTheBytesAreUtf8` tests (the loop fed a GENERAL array, which the
primitive declines).

**Two encoded surrogates side by side** (`ED A0 BD ED B8 80`) decode to two characters on the
interpreter and both wasm tiers, and to ONE on the JVM, whose string is UTF-16 and reads the pair as
U+1F600. A divergence in the string representation, not the decoder; unpinned in ci-spec.

**The wasm validator is deliberately NOT `_str_char_at`'s walk**, whose ranges accept overlong forms,
surrogates, code points past U+10FFFF and bare continuation bytes. Strict ranges: `C2..DF` one
continuation; `E0..EF` two (`E0` needs `A0..BF` first, `ED` needs `80..9F`); `F0..F4` three (`F0`
needs `90..BF`, `F4` needs `80..8F`); every continuation `10xxxxxx`; a truncated tail refused. The
lenient 4-byte arm re-tests the assembled code point.

## `%future-force`
Internal; resolves a future from SYNCHRONOUS code -- an ordinary function, so the lexical
await-placement rule does not apply. **In asyncMode it has TWO shapes**: with a scheduler it blocks
on the task waitable-set, without one it is a poll (`WasmFutureRuntimeBuilder.buildSyncForce`). Used
by the host-driven reactor transport (`http-reactor.lisp`, `.kb/clack.md`); deliberately
undocumented.

## `%future-race`
Internal, `--component` only, defined in sockets.lisp: a future settling to the INDEX of the first of
FUTURES to settle, fulfilled or rejected; a non-future or already-settled future wins at once. The
index, not the value, because the winner may answer nil (a timer always does) and keeps its own
error: the caller awaits the winner. It is the deadline substrate ([tcp-sockets.md](tcp-sockets.md),
"Read deadlines").
- **Lisp over the scheduler, not a `_sched_*` member**: one `async-lambda` arm per future, awaiting
  it under `handler-case` and then `%future-settle`-ing the race (first settle wins). The frame
  machinery wakes it, task ownership and the doorbell included, so a race made in a served handler
  resumes on its own task; a scheduler-level waiter kind would have had to restate that. An arm's
  await of an EXITED future throws `$block-exit` past it, so a race over a body that exits
  non-locally never settles -- no consumer races one.
- **The losers run on**: a loser holding a host operation is the caller's to withdraw (the socket
  read's `-cancel-read`); a losing timer fires later into an arm that finds the race settled. No
  `subtask.cancel` is bound.
- **Elsewhere** no backend needs one (`SO_TIMEOUT`, the `--native` runner's deadline and `FIONREAD`).
  A public `race` would need a native per backend.

## `%task-swap`
Internal, asyncMode only: `(rontolisp::%task-swap task)` makes TASK the CURRENT task record (nil for
none) and answers the one it replaces. A frame made with no current task belongs to none, so
`_wake_list` resumes it on the stack of whoever settles the future it waits for. Its one consumer is a
Java program's scheduler (`java.lisp`): a thread of the program outlives the request that started it
and is resumed while a later one is answered -- without the swap the settle went to an ended task's
ready list and doorbell, and the scheduler found every thread waiting. The swap moves the task record
alone: a waitable made meanwhile still joins the set of the task the host runs, so that scheduler
keeps the task in hand while a thread waits (`.kb/java-frontend.md`, "The host's events give the
turns under a component"). Not for a frame that answers its own task: the callback driver resumes
that on its task.

**A task that exits loses its pending waitables** (measured on wasmtime 49 and 51):
under `wasmtime serve` a handler that answered while an unawaited body awaited a timer never ran the
rest of that body -- the callback driver answers EXIT once the root future is fulfilled. A handler
that AWAITS is suspended instead, and the host enters the instance for other requests meanwhile.
Keeping a task until its set is empty was not made: a losing `%future-race` timer would hold every
such task for its whole deadline.

## `%future-settled-p`
Internal; whether a future has settled without waiting (`t` for a non-future). JVM
`_future_settled` is emitted only for a program naming it. It drives nothing: on a component a
pending future settles only while something blocks on the scheduler. Its consumer is Clojure's
`future-done?` and timed `deref` (`.kb/clojure-frontend.md`, "HTTP client"), pinned on all four legs
by `clojure-http-spec.yaml`.

## http-handler interaction
A handler that awaits must itself be an async-defun; the servers await its future (http.lisp's
`%serve-handle` is itself an async-defun, recognized by `HttpLibrary.defunName` for the splice
reachability walk). The default `:raw-body` is an asynchronous stream ON EVERY BACKEND; `:raw-body
:buffered` makes it a SYNCHRONOUS bivalent stream (`.kb/http-server.md`). A stream response body is
drained via `rontolisp::%http-drain`, not read-all.

## Tests
`AsyncEvalTest` / `JvmAsyncCompilerTest` pairs (`thenChainsOnFutureSettledValue`,
`streamNewBuildsAPullStreamOverAPairOfThunks` and its async-thunk / no-write-end edges,
`octetsDecodeNativelyWhetherOrNotTheBytesAreUtf8`, `streamVerbsSignalATypeErrorOverANonStream`);
`WasmLispCompilerIntegrationTest` `p1Then*` / `componentThen*` / `componentCatch*` /
`componentFinally*` / `preview1HasAFirstClassStreamValueOverAPairOfThunks` /
`streamVerbsSignalATypeErrorOverANonStream`;
`WasmLispCompilerTest.theP1StreamBlockRidesOnlyAStreamCreatingModule`;
`NoGcWasmCompilerTest.asyncAwaitSurfaceIsRejected`; `LispPreludeLibraryTest`;
`WasmHostStreamE2eTest`; ci-spec `future-as-value-combinators-then-catch-finally`,
`stream-new-builds-a-pull-stream-on-every-backend`, `stream-verbs-signal-a-type-error-over-a-non-stream`,
`read-all-passes-a-string-through`, `read-all-decodes-an-octet-chunk-stream`.
