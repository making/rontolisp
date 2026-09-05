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
  completes NORMALLY with `{EMARKER, throwable, condition}` and `_await` re-sets `_condTl` on the
  awaiting thread before rethrowing -- that is what makes handler-case dispatch across the await.
- **Preview-1 wasm-GC**: degenerate synchronous. `WasmAsyncRunCompiler` wraps the value in a settled
  kind-2 `TYPE_P1_FUTURE {mut i32 kind, mut value}` (the kind field exists so the shape does not
  canonicalize into `TYPE_CELL`); `_p1_future_await` (`FUNC_P1_FUTURE_AWAIT`) resolves. CAVEAT: an
  async body's ERROR signals at the CALL, not at await. **`--no-gc`** rejects the whole async surface
  by name, `%stream-new` included.

## `--component` (asyncMode)
- Async bodies compile as ENTRY+RESUME state machines over first-class `TYPE_FUTURE`s
  (`WasmAsyncEmit`); asyncMode FORCES EH mode.
- **Trap**: an async-defun's rewritten plain defun is EXCLUDED from the fusion-inlinable set even
  when a one-form body qualifies textually (`.kb/wasm-int-fusion.md`).
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

**The lenient loop is the FALLBACK: `%octets-to-string-strict` runs first**, NATIVE on every backend
-- bytes as STRICT UTF-8, or `nil` when not valid; only what it refuses walks a byte at a time.
`Environment.decodeUtf8Strict`; JVM `_utf8Strict` (`JvmAsyncRuntimeBuilder.buildOctetsStrict`); both
WASM GC tiers `_iv_utf8_str` (`WasmStringRuntimeBuilder.buildIvUtf8StrBody`, `FUNC_IV_UTF8_STR`).
**Deliberately NOT `_str_char_at`'s walk**, whose ranges accept overlong forms, surrogates, code
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
`octetsDecodeThroughTheStrictFastPathAndFallBackOnMalformedBytes`);
`WasmLispCompilerIntegrationTest` `p1Then*` / `componentThen*` / `componentCatch*` /
`componentFinally*` / `preview1HasAFirstClassStreamValueOverAPairOfThunks`;
`WasmLispCompilerTest.theP1StreamBlockRidesOnlyAStreamCreatingModule`;
`NoGcWasmCompilerTest.asyncAwaitSurfaceIsRejected`; `LispPreludeLibraryTest`;
`WasmHostStreamE2eTest`; ci-spec `future-as-value-combinators-then-catch-finally`,
`stream-new-builds-a-pull-stream-on-every-backend`, `read-all-passes-a-string-through`,
`read-all-decodes-an-octet-chunk-stream`.
