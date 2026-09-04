# async/await: async-defun, await, futures and asynchronous streams

## Surface

- `rontolisp:async-defun` / `rontolisp:async-lambda`, plus the `rontolisp:async` WRAPPER
  macro: `(async (defun ...))` == async-defun, `(async (lambda ...))` == async-lambda,
  anything else = a clear error. Pure frontend rewrite (`LispMacroExpander.expandAsync`
  single-form, `rewriteAsyncSugar` deep pass) run before EVERY consumer of the canonical
  forms: the CLI right after LoadInliner (so HttpLibrary / WitExportInliner / pruner
  definition scanners see async-defun), both compilers' `compile()` right after
  flattenTopLevel (direct invocations and the playground Compile buttons),
  `UserMacroExpander`'s expansion output, an evalCons case, and `LispAsync.check`/`lowerForm`.
  `rontolisp:async` joins NO introspection listing.
- `rontolisp:await` is a SPECIAL FORM. Predicates `futurep` / `streamp`; stream ops
  `make-stream` / `stream-read` / `stream-write` / `stream-close` / `read-all`; timer
  `rontolisp:wait-for` (ms -> a future settling to nil; deliberately NOT `sleep`, which
  exists with blocking-seconds semantics). `promisep` and `LispPromise` are DELETED.

## Cross-backend contract

- **Eager start**: calling an async function runs the body immediately until its first await
  of an UNSETTLED future (or completion); only then does the caller resume with the future.
  Output before the first suspension is identically ordered everywhere.
- **await placement is lexical**: legal inside async-defun/async-lambda bodies and at top
  level (implicitly async); illegal in any plain defun/lambda even nested inside an async body
  (the JavaScript rule). Checked by `am.ik.rontolisp.LispAsync` on the resolved AST --
  `checkTopLevel` (compilers) or the memoized `checkAwaitPlacement` (interpreter), which
  PRE-APPROVES the `%async-run` thunk lambdas the lowering synthesizes; without that,
  evaluating the lowered thunk re-rejects its awaits.
- Await of a settled future or a non-future never suspends; nested futures flatten; an
  errored future re-signals its condition AT AWAIT (handler-case catches it by type).
- **Lowering**: `(async-defun name (ll) body...)` ->
  `(defun name (ll) (rontolisp::%async-run (lambda () body...)))`
  (`LispMacroExpander.expandAsyncDefun`; deep pre-pass `LispAsync.lowerProgram` on compile
  paths, evalCons case on the interpreter). `%async-run` is the ONE backend primitive. The
  lambda-list stays on the outer defun, so `&optional` defaults run synchronously at entry.

## Per-backend implementation

- **Interpreter**: `eval/AsyncRuntime.run` = virtual thread + eager-start handoff latch
  (`releaseHandoffIfPending` called by await before blocking) -- deliberately the ONLY
  LispEvaluator-reachable thread site; the playground substitutes it
  (`src/web/java/.../Target_AsyncRuntime.java`, body runs synchronously; fetch still overlaps
  via the SAB broker). Values: `LispFuture` (wraps `CompletableFuture`) and `LispStream` --
  push mode (chunks + pending reads + close + fail, `fail` draining buffered chunks first) or
  PULL mode (read thunk + close thunk, no buffer, no write end). After the first suspension
  the body runs in REAL PARALLEL with the caller; global-state races are the user's business.
- **JVM**: `JvmAsyncRuntimeBuilder` (hand-assembled). A future is a bare `CompletableFuture`;
  a stream is `{SMARKER, LinkedBlockingQueue, AtomicInteger}` with the interned SMARKER
  re-enqueued as the EOF poison pill; `stream-read` returns an `{RMARKER, queue, state}` token
  whose blocking take happens at `_await`. The class `implements Runnable` (fields `_asyncFn`
  / `_asyncFuture` / `_asyncLatch`); `_async_run` spawns `Thread.ofVirtual()`. **An error
  cannot ride the `_condTl` ThreadLocal across threads**, so `run()` completes the future
  NORMALLY with `{EMARKER, throwable, condition}` and `_await` re-sets `_condTl` on the
  awaiting thread before rethrowing -- that is what makes handler-case dispatch across the
  await. `_handoffTl` shares the `<clinit>` with `_condTl`. Prints `#<FUTURE>`, `#<STREAM>`.
- **Preview-1 wasm-GC**: degenerate synchronous. `%async-run` (`WasmAsyncRunCompiler`) calls
  the thunk through dispatch-0 and wraps the value in a settled kind-2
  `TYPE_P1_FUTURE {mut i32 kind, mut value}`; the kind field survives only so the shape does
  not structurally canonicalize into the one-field `TYPE_CELL`. A
  `rontolisp:wasm-import ... :async t` wrapper is its other producer (`.kb/wasm-import.md`).
  `_p1_future_await` (`FUNC_P1_FUTURE_AWAIT`) is P1's resolver; `futurep` ref.tests it.
  CAVEAT: an async body's ERROR signals at the CALL, not at await.
- **`--no-gc`**: the whole async surface is rejected by name with `... is not supported with
  --no-gc (use the default GC backend)`, `%stream-new` included, so the diagnostic names the
  primitive rather than the struct it cannot build.

### `--component` (asyncMode)

- async-defun/async-lambda (and a top level with awaits) compile as ENTRY+RESUME state
  machines over first-class `TYPE_FUTURE`s (`WasmAsyncEmit`). asyncMode FORCES EH mode.
- **Trap**: an async-defun's rewritten plain defun is EXCLUDED from the fusion-inlinable set
  even when a one-form body qualifies textually -- splicing the raw body bypasses the state
  machine and hands a synchronous caller the value instead of the future
  (`.kb/wasm-int-fusion.md`).
- An `async func` wit-import member returns a pending `TYPE_FUTURE` through
  `rontolisp::%subtask-future` (registry + the CURRENT task's waitable-set); events are
  dispatched by the shared core `_sched_dispatch` (`WasmFutureRuntimeBuilder`) under TWO
  drivers.
- **Blocking driver `_sched_loop`** (a `waitable-set.wait` loop; base component-model-async
  and wasmCloud-legal) runs suspensions to completion at the synchronous boundaries: the
  top-level `_start` entry and a non-serve wasm-export wrapper whose target answered a pending
  future (EVERY asyncMode export polls dynamically -- a plain defun may pass an async
  function's future through; `.kb/wasm-export-no-wasi.md`).
- **Callback driver** at serve's `handle` boundary: the wrapper begins a task record
  (`_task_begin`; frames created while it runs carry it as their OWNER, the 5th
  `TYPE_ASYNC_FRAME` field). A pending handler does NOT block -- `_task_suspend` arms the
  task's doorbell, registers the record, stores the task id in **context slot 0** (wasmtime 46
  validates the `context.get/set` immediate to 0, so the ONE slot holds the id and the
  waitable-set handle rides the record) and returns the packed `WAIT | (set << 4)` code. The
  host feeds each event of the task's set to the core-exported `_async_cb` (`async_cb`), which
  restores the task identity, dispatches, drains and answers WAIT again or EXIT (`task.return`
  delivered the response mid-task).
- **Cross-task wakeup is a per-task DOORBELL**: every callback task owns an intra-component
  `stream<u64>` with a standing pending read joined into its set (the `$sched` canon built-ins
  the serve builder synthesizes). `_wake_list` resumes a waiter directly only when its frame's
  owner IS the current task (or null -- a synchronous boundary's frame, which never
  task-returns), otherwise appends to the owner's ready list and rings the doorbell on the
  empty-to-nonempty transition. The owner's callback re-arms the read BEFORE draining, so no
  wakeup is lost.
- Tasks are cooperative and single-threaded; two requests can interleave in ONE instance. A
  completed task's doorbell ends and waitable-set are LEAKED (bounded by requests served on a
  reused instance; hosts today re-instantiate).
- Component streams: `TYPE_WASI_STREAM {eof, readFn, closeFn}` wraps the wasi-backed body
  streams http.lisp produces (`%stream-new` over two arity-0 Lisp thunks; the close protocol
  lives in http.lisp, runs once at EOF or stream-close). A read of an in-flight chunk is a
  PENDING future: the wrapper registers it on the scheduler registry -- entries
  `(waitable . (kind . (future . data)))`, kind 0 = subtask, kind 1 = stream read with its
  staged buffer (recycled through a free-list global; memory cost bounded by CONCURRENT
  reads) -- joins the stream handle into the task waitable-set, and `_sched_loop`'s
  EVENT_STREAM_READ dispatch lifts the chunk, runs the EOF close protocol and settles it. A
  SECOND read on the same stream before the first settles is a host trap ("read already
  pending"); interpreter/JVM queue instead. Guest `make-stream`/`stream-write` stay compile
  errors; the write-side built-ins keep the blocking waitable-set park.

### `wait-for`

- Interpreter `AsyncRuntime.timer` and JVM `_wait_for`: both
  `new CompletableFuture().completeOnTimeout(nil, ms, MILLISECONDS)` (the JDK shared delayer,
  no new thread site; the Web Image substitution settles immediately).
- `--component`: the `wait.lisp` shim (spliced by `eval/WaitForLibrary`) over a wit-imported
  `wasi:clocks/monotonic-clock@0.3.0` `wait-for` (ns; the defun converts ms and validates). An
  async import call, so it returns a PENDING `TYPE_FUTURE` via `%subtask-future` that
  `_sched_loop` settles on EVENT_SUBTASK -- timers genuinely overlap (delay order, not start
  order). The interface is in the fixed import block on every GC variant, so
  `WasmComponentBuilder.lowerFixedFromBlock` binds it FROM the block instead of re-importing
  (`FIXED_BLOCK_IFACES`; a hand-written wit-import of monotonic-clock may bind only `now` and
  `wait-for`).
- Preview 1 keeps the compile error (no host timer); `--no-gc` keeps the async rejection.

## Future-as-value combinators (`then` / `then*` / `catch` / `finally`)

Lisp-prelude `defun`s in `LispPreludeLibrary.SOURCES` (alongside `read-all`), one definition
for every backend, each expanding to `funcall` of an `async-lambda` whose body uses `await` +
`handler-case`/`unwind-protect` -- so the WASM EH-mode gate flips automatically and every
backend picks them up through the normal prelude splice / lazy load. They inherit the
eager-start contract, condition dispatch across await, the JVM `_condTl` restore, the
component scheduler integration, and the `--no-gc` rejection (their names go at the FRONT of
`NoGcWasmCompiler`'s list so the diagnostic points at `rontolisp:then`, not the downstream
`async-lambda`). Preview 1 supports only the success half (an errored async body signals AT
THE CALL), so a `catch`/`finally` error-arm cannot fire there. A non-future first argument is
a `type-error` on every backend -- no JS-style coercion.

Pins: `AsyncEvalTest.thenChainsOnFutureSettledValue`, the `JvmAsyncCompilerTest` mirror,
`WasmLispCompilerIntegrationTest.p1Then*` / `componentThen*` /
`componentCatchRunsOnlyWhenUpstreamSignals` /
`componentFinallyRunsOnBothPathsAndPreservesOutcome`,
`NoGcWasmCompilerTest.asyncAwaitSurfaceIsRejected`, ci-spec
`future-as-value-combinators-then-catch-finally`.

## `%stream-new`, the four-backend pull stream

`rontolisp::%stream-new` (internal) is the ONE producer of a first-class PULL stream and takes
a read thunk, a close thunk, and a drained flag the runtime keeps. Nothing about it is WASI,
which is why one primitive serves both WASM tiers. `WasmStreamCompiler` picks the tier;
`WasmFutureInternalCompiler` builds the struct.

| backend | representation | where |
| --- | --- | --- |
| interpreter | PULL-mode `LispStream` (`LispStream.pull`) | `LispEvaluator`, beside `%async-run`/`%future-force` |
| JVM | `{SMARKER, {readFn, closeFn}, AtomicInteger}` | `JvmAsyncRuntimeBuilder._stream_new` |
| `--component` | `TYPE_WASI_STREAM {eof, readFn, closeFn}` | `WasmFutureInternalCompiler` |
| Preview 1 / `--no-wasi` | `TYPE_P1_STREAM`, same three fields | ditto |

- **The thunk's answer is resolved AT THE READ, before the end-of-stream test**, on every
  tier: interpreter in the callback `LispEvaluator` closes over (`awaitValue` of the applied
  thunk, so `LispStream` never sees a future and the root package still imports nothing from
  `eval`); JVM `_stream_read` via `_await`; P1 via `_p1_future_await`. On P1 a
  `wasm-import ... :async t` call and an `async-lambda` both answer a settled future, and a
  future wrapping nil is not nil -- without the resolve such a thunk could never report EOF.
- **A pull stream has no write end**: `stream-write` on one is its own refusal ("the stream
  has no write end"), not "the stream is closed". The JVM's `_drain_body` reads through
  `_stream_read` + `_await` rather than off the queue, so ONE drain serves both modes. Guest
  `make-stream` stays interpreter/JVM-only.
- P1 tier `WasmP1StreamRuntimeBuilder`: `_p1_stream_read` answers a SETTLED `TYPE_P1_FUTURE`
  (nothing there can suspend -- no pending arm, no scheduler), `_p1_stream_close` runs the
  close thunk once. The first nil chunk flips `eof` and runs the close protocol, so a drain
  closes exactly once and a read past EOF is nil.
- **Gated on `%stream-new` appearing** (`WasmLispCompiler.usesP1Streams`): the type goes at
  `p1StreamTypeBase()` and the two functions at `p1StreamFuncBase()` -- the slots the async
  block would have used, which cannot be present at the same time -- so no other index moves
  and a stream-free module is byte-identical
  (`WasmLispCompilerTest.theP1StreamBlockRidesOnlyAStreamCreatingModule`). A module that can
  hold NO stream keeps the call-time error stub for `stream-read`/`stream-close`, but
  `streamp` there is the CONSTANT NIL rather than an error.

Gates: ci-spec `stream-new-builds-a-pull-stream-on-every-backend`, the per-backend
`AsyncEvalTest`/`JvmAsyncCompilerTest` pairs (`streamNewBuildsAPullStreamOverAPairOfThunks`
plus the async-thunk / no-write-end edges),
`WasmLispCompilerIntegrationTest.preview1HasAFirstClassStreamValueOverAPairOfThunks`,
`WasmHostStreamE2eTest`.

## `read-all` is prelude Lisp

An `async-defun` in `LispPreludeLibrary` (one definition for every backend), lazy-loaded on
the interpreter and spliced on the compile paths -- **so compiler tests that use it must
mirror the CLI's `LispPreludeLibrary.process` pre-pass** (JvmLispCompilerTest's compileAndRun
does).

- A `stringp` arm before the drain loop PASSES A STRING THROUGH, so
  `(await (read-all (getf res :body)))` is target-free. `:body` is a stream on all four
  backends (`.kb/fetch-http.md`); the arm remains for a user plist and the declared
  absent-body default `""`.
- Two chunk kinds: STRING chunks (a guest `make-stream`) concatenated through a string output
  stream (not pairwise -- quadratic); OCTET chunks (`(unsigned-byte 8)` vectors, what every
  HTTP body stream answers) collected, joined once by `rontolisp::%octets-join` (an aref/aset
  blit; a single chunk is answered uncopied) and decoded once by
  `rontolisp::%octets-to-string`, the LENIENT UTF-8 decoder (a byte that leads no valid
  sequence, or a truncated sequence, is its own character -- `http-server.lisp`'s
  request-decoder rule). A stream mixing the two kinds is an error.
- `%octets-to-string` is Lisp in the prelude for the compile paths and a native Java mirror in
  `Environment` on the interpreter (the `char-name` arrangement: the interpreter finds the
  native first and never loads the Lisp one). `LispPreludeLibraryTest` pins the two arm for
  arm.

Pins: AsyncEvalTest / JvmAsyncCompilerTest, ci-spec `read-all-passes-a-string-through` and
`read-all-decodes-an-octet-chunk-stream`.

### The lenient loop is the FALLBACK: `%octets-to-string-strict`

Runs first, NATIVE on every backend: the bytes decoded as STRICT UTF-8, or `nil` when they are
not valid UTF-8 (and for any value that is not a packed octet vector, handing the general case
back to the loop). Only what it refuses walks a byte at a time. The two agree BY CONSTRUCTION:
on well-formed input the strict answer is what the arms would have built.

| backend | how |
| --- | --- |
| interpreter | `Environment.decodeUtf8Strict` -- `CharsetDecoder` with its default REPORT action, `null` on `CharacterCodingException` |
| JVM | `_utf8Strict` (`JvmAsyncRuntimeBuilder.buildOctetsStrict`, beside `_iv_of_bytes`): `long[]` unpacked to `byte[]`, same decoder, result framed in the storage quotes; emitted only when the program references the primitive |
| WASM GC (both tiers) | `_iv_utf8_str` (`WasmStringRuntimeBuilder.buildIvUtf8StrBody`, `FUNC_IV_UTF8_STR`): a strict validator over `TYPE_I8ARR`, then ONE `array.copy` into a fresh `$str_bytes` between the two quote bytes |

**The wasm validator is deliberately NOT `_str_char_at`'s walk**: that one only needs a byte
COUNT per lead byte, so its ranges accept overlong forms, surrogates, code points past
U+10FFFF and bare continuation bytes -- under which a raw copy is wrong. Strict ranges:
`C2..DF` leads one continuation; `E0..EF` two (`E0` needs `A0..BF` first, `ED` needs `80..9F`);
`F0..F4` three (`F0` needs `90..BF`, `F4` needs `80..8F`); every continuation is `10xxxxxx`; a
truncated tail is refused.

The lenient loop's 4-byte arm now re-tests the assembled code point and falls to the
own-character arm when out of range (previously an `#xF5` lead or an `#xF4` past U+10FFFF was
a hard error on the JVM, an out-of-range character on wasm, four characters on the
interpreter). `LispPreludeLibraryTest` walks both sides of every strict boundary; per-backend
pins are `octetsDecodeThroughTheStrictFastPathAndFallBackOnMalformedBytes` in AsyncEvalTest,
JvmAsyncCompilerTest and WasmLispCompilerIntegrationTest (both wasm tiers).

## `%future-force`: the function spelling of the resolve

Internal; resolves a future from SYNCHRONOUS code -- an ordinary function, so the lexical
await-placement rule does not apply. Interpreter = `awaitValue`; JVM = the `_await` helper
(emitted by `JvmAwaitCompiler` under the caller-supplied name, usage joins the async-runtime
gate); non-asyncMode WASM = `_p1_future_await`. Used by the host-driven reactor transport
(`http-reactor.lisp`) to resolve a future-valued application answer at its boundary
(`.kb/clack.md`). Deliberately undocumented -- user code composes with `await`/`then`. A
`rontolisp:wasm-export` boundary does not need it spelled in the target: the wrapper resolves
a returned future itself (`.kb/wasm-export-no-wasi.md`).

**In asyncMode it is `_sched_loop`, with TWO shapes.** With a scheduler (the module binds an
async-calling interface) it blocks on the task waitable-set until the driven future settles.
Without one it is a poll (`WasmFutureRuntimeBuilder.buildSyncForce` -> `OFF_POLL`; a plain
value passes through, a settled chain flattens, a rejection re-signals) -- nothing in such a
module CAN suspend, so every future is settled by the time anything forces it. Pinned by the
`%future-force` line of `stream-new-builds-a-pull-stream-on-every-backend`.

## http-handler interaction

A handler that awaits (fetch inside serve) must itself be an async-defun; the servers await
the handler's future (interpreter `invokeHttpHandler`, the JVM generated `handle()`,
http.lisp's `%serve-handle` -- itself an async-defun, recognized by `HttpLibrary.defunName`
for the splice reachability walk). The default `:raw-body` is an asynchronous stream ON EVERY
BACKEND (the component wraps the wasi request body; interpreter/JVM buffer into one settled
chunk); under `:raw-body :buffered` it is a SYNCHRONOUS bivalent stream and no await is
involved on the request side (`.kb/http-server.md`). A stream response body is drained before
sending (buffered transport v1) via http-server.lisp's `rontolisp::%http-drain` -- not the
prelude's read-all; both libraries stay self-contained.
