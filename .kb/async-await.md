# async/await: async-defun, await, futures and asynchronous streams

## Surface
- `rontolisp:async-defun` / `rontolisp:async-lambda`, plus the `rontolisp:async` WRAPPER macro
  (`(async (defun ...))`, `(async (lambda ...))`, else an error). Pure frontend rewrite
  (`LispMacroExpander.expandAsync`, `rewriteAsyncSugar`) run before EVERY consumer of the
  canonical forms: the CLI after LoadInliner, both compilers' `compile()` after flattenTopLevel,
  `UserMacroExpander`'s output, an evalCons case, `LispAsync.check`/`lowerForm`. `rontolisp:async`
  joins NO introspection listing.
- `rontolisp:await` is a SPECIAL FORM. `futurep` / `streamp`; `make-stream` / `stream-read` /
  `stream-write` / `stream-close` / `read-all`; `rontolisp:wait-for` (ms -> future settling to
  nil; NOT `sleep`). `promisep` and `LispPromise` are DELETED.

## Cross-backend contract
- **Eager start**: the body runs immediately until its first await of an UNSETTLED future, so output
  before the first suspension is identically ordered everywhere. Await of a settled future or a
  non-future never suspends; nested futures flatten; an errored future re-signals AT AWAIT.
- **Values**: `await` answers every value the body answered ("Multiple values", below).
- **await placement is lexical**: legal in async bodies and at top level, illegal in any plain
  defun/lambda even nested inside an async body (the JavaScript rule).
  `am.ik.rontolisp.LispAsync.checkTopLevel` (compilers) / memoized `checkAwaitPlacement`
  (interpreter), which PRE-APPROVES the `%async-run` thunk lambdas the lowering synthesizes.
- **Lowering**: `(async-defun name (ll) body...)` ->
  `(defun name (ll) (rontolisp::%async-run (lambda () body...)))` (`expandAsyncDefun`;
  `LispAsync.lowerProgram` on compile paths, evalCons case on the interpreter). `%async-run` is
  the ONE backend primitive; the lambda-list stays on the outer defun.

## Per-backend implementation
- **Interpreter** `eval/AsyncRuntime.run` = virtual thread + eager-start handoff latch
  (`releaseHandoffIfPending`), the ONLY LispEvaluator-reachable thread site; the playground
  substitutes it (`src/web/java/.../Target_AsyncRuntime.java`). `LispFuture`; `LispStream` push or PULL.
- **JVM** `JvmAsyncRuntimeBuilder`: future = bare `CompletableFuture`; stream =
  `{SMARKER, LinkedBlockingQueue, AtomicInteger}`, SMARKER re-enqueued as EOF poison pill;
  `stream-read` returns an `{RMARKER, queue, state}` token taken at `_await`. The class
  `implements Runnable`; `_async_run` spawns
  `Thread.ofVirtual()`. **An error cannot ride the `_condTl` ThreadLocal across threads**: `run()`
  completes NORMALLY with `{EMARKER, throwable, condition}` (the thunk having appended a
  `rontolisp/async.crossed` frame to its trace when the class carries location lines, and the
  payload then carrying that trace as a fourth element for each await to put back,
  [error-handling.md](error-handling.md)) and `_await` re-sets `_condTl` on the
  awaiting thread before rethrowing -- that is what makes handler-case dispatch across the await.
- **Preview-1 wasm-GC**: degenerate synchronous. `WasmAsyncRunCompiler` wraps the value in a settled
  kind-2 (kind 4 for several values, "Multiple values") `TYPE_P1_FUTURE {mut i32 kind, mut value}` (the kind field exists so the shape does not
  canonicalize into `TYPE_CELL`); `_p1_future_await` (`FUNC_P1_FUTURE_AWAIT`) resolves. The one
  UNSETTLED kind is 3, `rontolisp::%future-deferred`'s: its value is a thunk every await runs (a
  `--native` fetch's, `.kb/fetch-http.md`), and the await runtime carries that arm only in a module
  that names the primitive. **An async body's ERROR signals at the await, as everywhere else, in an
  EH-mode module**: `%async-run` catches `$lisp-cond` around the thunk and settles a FAILED future,
  kind 5, over the payload, which every await throws again (the same payload, so what
  `--report-locations`' frames noted about it survives, rewound per await,
  [error-handling.md](error-handling.md)). Until 2026-09-26 it signalled at the CALL: output
  between the call and the await never printed, and a `handler-case` around the await -- or
  `rontolisp:catch` -- never saw it. Outside EH mode nothing can catch a condition, so the body
  still traps at the call (the one observable difference left: output between call and await).
  Measured 2026-09-26 on 2f611be61 (EH mode, bytes): the clack/ningle/tiny-routes examples and
  their Cloudflare workers +74 each, `postgres-web` +122; a toy `(print (ignore-errors (await (job
  21))))` 5,571 -> 6,749 -- the payload joins the future's value field, so the type-test fold keeps
  the printer's cons arm (`--component`'s rejected future has the same shape). Every other example,
  size-report and bench program is byte-identical at default, `--component` and `--optimize=size`.
  **`--no-gc`** rejects the whole async surface by name, `%stream-new` included.

## Multiple values
**Invariant (2026-09-26): an async body's values reach its awaiter the way a function call's
do.** The future settles with the body's full value list, captured where the body completes, on
the thread (or in the resume) that ran it; `await` answers the primary and publishes the extras
to the `%mv-spill` channel (`.kb/multiple-values.md`). The last future of a flattened chain
decides; a non-future operand, and every future no async body made (fetch, wait-for, a stream
read, a subtask), is one value. Pinned by `AwaitValuesMatrix` (literal, zero, syntactic-producer,
called, nested, tail-awaited and stale-publish bodies; a consumer inside a body; an async-lambda;
a second await of one future; an unrelated publish between call and await) on all four backends
-- `AsyncEvalTest`/`JvmAsyncCompilerTest`/`WasmLispCompilerIntegrationTest` `*AwaitAnswersEvery*`
and the `await-answers-every-value-of-the-async-body` ci-spec case -- plus its suspending half
(not on Preview 1) and `CONCURRENT_PROGRAM`, sixty bodies consuming values in parallel.
- Before, `await` answered one value (`singleValue`, acf4b247a) and a program written for the
  pre-acf4b247a behaviour -- which read the body's publish back out of the process-wide channel,
  by accident -- got a silent NIL far from the cause.
- The capture reads the channel right after the body's tail, which the tail settle made exact
  (`settleLambdaTails` + the backends' lambda CLEAR for the `%async-run` thunk,
  `settleDefunTails` for a component's rewritten async-defun, `settleFunctionBody` for its
  async-lambda). It is race-free only because the channel is one per thread
  (`.kb/multiple-values.md`, "One register per thread").
- Interpreter: `%async-run`'s body calls `LispFuture.settleExtras(spill)` before answering the
  primary; `awaitValues` publishes the extras of the last future it joined.
- JVM: `run()` completes with `{VMARKER, primary, extras}` when the channel is non-nil;
  `_await` clears the channel, publishes a VMARKER payload's extras and flattens its primary.
- Preview 1: `%async-run` settles a `KIND_VALUES` (4) `TYPE_P1_FUTURE` over `(primary . extras)`
  when the channel is non-nil; `_p1_future_await` clears the channel and publishes them.
- `--component`: `TYPE_FUTURE`'s fourth field (unused before) holds the extras: the resume
  writes it into its frame's future as it completes (not the top-level resume), the ENTRY clears
  the channel on all three exits (it answers one value, the future), and `_future_poll` clears
  the channel and publishes each fulfilled hop's extras.
- All of it is gated on the spill global: a program with no multiple-value consumer is
  byte-identical on the three compile targets (fib, fib + a consumer, an async program with no
  consumer). With one (2026-09-26): the combinator ci-spec case + one consumer, class 15,578 ->
  16,014 B, wasm 13,849 -> 14,015 B, component 21,679 -> 21,806 B. An `await` inside a consumer
  now takes the channel round trip instead of the one-temporary path, which on `--component` is
  dearer at TOP LEVEL, where every mirrored local is spilled at every suspend site (the matrix
  program, fourteen such consumers: 31,056 -> 37,965 B). 300,000 async calls + awaits: Preview 1
  17-21 -> 17-22 ms, component 40-66 -> 42-56 ms, JVM 3,229-4,412 -> 3,203-3,763 ms -- noise.

## `--component` (asyncMode)
- Async bodies compile as ENTRY+RESUME state machines over first-class `TYPE_FUTURE`s
  (`WasmAsyncEmit`); asyncMode FORCES EH mode.
- **Trap**: an async-defun's rewritten plain defun is EXCLUDED from the fusion-inlinable set even
  when a one-form body qualifies textually (`.kb/wasm-int-fusion.md`).
- **Trap**: a region's landing pad must not restore the resume target `$rt`; one that did skipped
  everything after a `handler-case` that caught on a RESUMED frame (`.kb/wasm-landing-pad-refresh.md`,
  "`$rt`").
- An `async func` wit-import member returns a pending `TYPE_FUTURE` via
  `rontolisp::%subtask-future`; events dispatch through the shared core `_sched_dispatch`
  (`WasmFutureRuntimeBuilder`) under TWO drivers.
- **Blocking driver `_sched_loop`** (`waitable-set.wait`, wasmCloud-legal) at `_start` and at a
  non-serve wasm-export wrapper whose target answered a pending future (every asyncMode export
  polls dynamically, `.kb/wasm-export-no-wasi.md`).
- **Callback driver** at serve's `handle`: `_task_begin` starts a task record (frames carry it as
  OWNER, the 5th `TYPE_ASYNC_FRAME` field); `_task_suspend` arms the doorbell, registers the record,
  stores the task id in **context slot 0** (wasmtime 46 validates the immediate to 0, so the
  waitable-set handle rides the record) and returns `WAIT | (set << 4)`; the host feeds events to the
  core-exported `_async_cb`.
- **Cross-task wakeup is a per-task DOORBELL** (an intra-component `stream<u64>` with a standing
  pending read in the set): `_wake_list` resumes a waiter directly only when its frame's owner IS the
  current task (or null), else appends to the owner's ready list and rings the doorbell on the
  empty-to-nonempty transition, the callback re-arming the read BEFORE draining. Tasks are
  cooperative and single-threaded; a completed task's doorbell ends and waitable-set are LEAKED.
- Component streams `TYPE_WASI_STREAM {eof, readFn, closeFn}`: a read of an in-flight chunk is a
  PENDING future on the scheduler registry (`(waitable . (kind . (future . data)))`, kind 0 = subtask,
  kind 1 = stream read with a free-list-recycled staged buffer) settled by `_sched_loop`'s
  EVENT_STREAM_READ. A SECOND read before the first settles is a host trap; interpreter/JVM queue.
  Guest `make-stream`/`stream-write` stay compile errors.
- **A DROPPED read completion is LATCHED per readable handle.** The result is `(n << 4) | code`
  and DROPPED beats COMPLETED, so one read can deliver its last items AND the end (`Dropped(n)`,
  n > 0); the host then traps any further `stream.read` ("cannot read after being notified that
  the writable end dropped"). Every completion path -- the blocking wrapper, the immediate
  asyncMode arm, `_sched_dispatch`'s kind 1/2 -- pushes the handle onto one global cons list when
  the code is DROPPED (n = 0 too), and the read wrapper answers a listed handle nil without
  touching it. The stream's `drop-readable` unlinks it (handle numbers are reused). The global
  exists only when a stream read is bound, after the render pair. wasmtime 49 produces
  `Dropped(n)` intra-component: an undelivered `Completed(n)` read event merges with the writer's
  drop (`update_event`). Pinned by `WasmLispCompilerIntegrationTest.componentStreamRead*`.

## `wait-for` and the combinators
Interpreter `AsyncRuntime.timer` and JVM `_wait_for` are `completeOnTimeout(nil, ms, MILLISECONDS)`
(JDK shared delayer, no new thread site). `--component` uses the `wait.lisp` shim
(`eval/WaitForLibrary`) over wit-imported `wasi:clocks/monotonic-clock@0.3.0` `wait-for` (ns), an
async import call, so timers overlap in delay order; the interface is in the fixed import block, so
`WasmComponentBuilder.lowerFixedFromBlock` binds it FROM the block (`FIXED_BLOCK_IFACES`). Preview 1
keeps the compile error; `--no-gc` the rejection.

`then` / `then*` / `catch` / `finally` are Lisp-prelude `defun`s in `LispPreludeLibrary.SOURCES` (with
`read-all`), one definition for every backend, each `funcall`ing an `async-lambda` over `await` +
`handler-case`/`unwind-protect`, so the WASM EH-mode gate flips automatically. Their names go at the
FRONT of `NoGcWasmCompiler`'s list so the diagnostic points at `rontolisp:then`. Preview 1 supports
only the success half. A non-future first argument is a `type-error` everywhere.

## `%stream-new`, the four-backend pull stream
`rontolisp::%stream-new` (internal) is the ONE producer of a first-class PULL stream: read thunk,
close thunk, drained flag. Nothing about it is WASI, which is why one primitive serves both WASM
tiers (`WasmStreamCompiler` picks the tier, `WasmFutureInternalCompiler` builds the struct).
`LispStream.pull` / JVM `{SMARKER, {readFn, closeFn}, AtomicInteger}` (`_stream_new`) /
`TYPE_WASI_STREAM` / `TYPE_P1_STREAM`.

- **The thunk's answer is resolved AT THE READ, before the end-of-stream test**, on every tier
  (interpreter `awaitValue` in the callback, so `LispStream` never sees a future; JVM `_await`;
  P1 `_p1_future_await`). A future wrapping nil is not nil -- without the resolve such a thunk
  could never report EOF.
- **A pull stream has no write end**: `stream-write` refuses with "the stream has no write end",
  not "the stream is closed". The JVM's `_drain_body` reads through `_stream_read` + `_await`, so
  ONE drain serves both modes. P1 `WasmP1StreamRuntimeBuilder`: `_p1_stream_read` answers a
  SETTLED future; the first nil chunk flips `eof`.
- **Gated on `%stream-new` appearing** (`WasmLispCompiler.usesP1Streams`): the type goes at
  `p1StreamTypeBase()` and the functions at `p1StreamFuncBase()` -- the slots the async block
  would have used, which cannot coexist -- so no index moves and a stream-free module is
  byte-identical. A module that can hold NO stream keeps the call-time error stub, but `streamp`
  there is the CONSTANT NIL rather than an error.

## `read-all` is prelude Lisp
An `async-defun` in `LispPreludeLibrary` -- **compiler tests that use it must mirror the CLI's
`LispPreludeLibrary.process` pre-pass**. A `stringp` arm before the drain loop PASSES A STRING
THROUGH. Two chunk kinds: STRING chunks concatenated through a string output stream (not pairwise --
quadratic); OCTET chunks joined by `rontolisp::%octets-join` and decoded by
`rontolisp::%octets-to-string`, the LENIENT UTF-8 decoder (a byte leading no valid sequence, or a
truncated one, is its own character). Mixing kinds is an error. `%octets-to-string` is prelude Lisp
for the compile paths and a native `Environment` mirror on the interpreter (the `char-name`
arrangement); `LispPreludeLibraryTest` pins the two arm for arm.

**The lenient loop is the FALLBACK, for a general array only: `%octets-to-string-packed` runs
first**, NATIVE on every backend, and answers every packed `(unsigned-byte 8)` vector itself
(`nil` for any other value): STRICT UTF-8 first (a platform decode; one `array.copy` on wasm), the
lenient arms as a native transcode for the rest. `Environment.decodeUtf8Strict` /
`decodeUtf8Leniently`; JVM `_octetsToString` (`JvmAsyncRuntimeBuilder.buildOctetsToString`, the
transcode in the `CharacterCodingException` handler); both WASM GC tiers `_iv_utf8_str`
(`WasmStringRuntimeBuilder.buildIvUtf8StrBody`, `FUNC_IV_UTF8_STR`, a two-pass transcode behind the
validator). Until 2026-09-26 the primitive was `%octets-to-string-strict` (`nil` on malformed
bytes) and a binary body walked the compiled loop: ~350-700 ns a byte, and on a `--native` output a
256 MiB body exhausted the GC heap (`.kb/fetch-http.md`, "Throughput"). The native transcodes are
pinned against the loop case for case by the three `octetsDecodeNativelyWhetherOrNotTheBytesAreUtf8` tests (the loop fed a
GENERAL array, which the primitive declines) and `LispPreludeLibraryTest`.
**The validator is deliberately NOT `_str_char_at`'s walk**, whose ranges accept overlong forms, surrogates, code
points past U+10FFFF and bare continuation bytes. Strict ranges: `C2..DF` one continuation; `E0..EF`
two (`E0` needs `A0..BF` first, `ED` needs `80..9F`); `F0..F4` three (`F0` needs `90..BF`, `F4` needs
`80..8F`); every continuation `10xxxxxx`; a truncated tail refused. The lenient 4-byte arm re-tests
the assembled code point and falls to the own-character arm when out of range.

## `%future-force`
Internal; resolves a future from SYNCHRONOUS code -- an ordinary function, so the lexical
await-placement rule does not apply. Interpreter `awaitValue`; JVM `_await` (`JvmAwaitCompiler`);
non-asyncMode WASM `_p1_future_await`; **in asyncMode `_sched_loop`, with TWO shapes** -- with a
scheduler it blocks on the task waitable-set, without one it is a poll
(`WasmFutureRuntimeBuilder.buildSyncForce` -> `OFF_POLL`). Used by the host-driven reactor transport
(`http-reactor.lisp`, `.kb/clack.md`); deliberately undocumented.

## http-handler interaction
A handler that awaits must itself be an async-defun; the servers await its future (interpreter
`invokeHttpHandler`, the JVM generated `handle()`, http.lisp's `%serve-handle` -- itself an
async-defun, recognized by `HttpLibrary.defunName` for the splice reachability walk). The default
`:raw-body` is an asynchronous stream ON EVERY BACKEND; `:raw-body :buffered` makes it a SYNCHRONOUS
bivalent stream with no await on the request side (`.kb/http-server.md`). A stream response body is
drained via `rontolisp::%http-drain`, not read-all.

## Tests
`AsyncEvalTest` / `JvmAsyncCompilerTest` pairs (`thenChainsOnFutureSettledValue`,
`streamNewBuildsAPullStreamOverAPairOfThunks` and its async-thunk / no-write-end edges,
`octetsDecodeNativelyWhetherOrNotTheBytesAreUtf8`);
`WasmLispCompilerIntegrationTest` `p1Then*` / `componentThen*` / `componentCatch*` /
`componentFinally*` / `preview1HasAFirstClassStreamValueOverAPairOfThunks`;
`WasmLispCompilerTest.theP1StreamBlockRidesOnlyAStreamCreatingModule`;
`NoGcWasmCompilerTest.asyncAwaitSurfaceIsRejected`; `LispPreludeLibraryTest`;
`WasmHostStreamE2eTest`; ci-spec `future-as-value-combinators-then-catch-finally`,
`stream-new-builds-a-pull-stream-on-every-backend`, `read-all-passes-a-string-through`,
`read-all-decodes-an-octet-chunk-stream`.
