# async/await: async-defun, await, futures and asynchronous streams

The user surface is `rontolisp:async-defun` / `rontolisp:async-lambda` (defining forms)
plus the `rontolisp:async` WRAPPER macro -- `(async (defun ...))` == async-defun,
`(async (lambda ...))` == async-lambda, anything else inside = a clear error; a pure
frontend rewrite (`LispMacroExpander.expandAsync` single-form,
`rewriteAsyncSugar` deep pass) run before EVERY consumer of the canonical forms:
the CLI right after LoadInliner (so HttpLibrary/WitExportInliner/pruner
definition scanners see async-defun), both compilers' `compile()` right after
flattenTopLevel (direct invocations + the playground Compile buttons -- the
wit-export lesson), UserMacroExpander's expansion output (macro-generated sugar),
an evalCons case on the interpreter, and `LispAsync.check`/`lowerForm` cases (a
sugar lambda nested in a plain defun body is checked through its expansion).
`rontolisp:async` joins NO introspection listing, like async-defun/async-lambda.
Then `rontolisp:await` (a SPECIAL FORM), the predicates `rontolisp:futurep` /
`rontolisp:streamp`, the stream operations `rontolisp:make-stream` /
`stream-read` / `stream-write` / `stream-close` / `read-all`, and the timer
`rontolisp:wait-for` (milliseconds -> a future settling to nil; deliberately
NOT named sleep -- `cl:sleep` exists with blocking-seconds semantics). The
promise-era vocabulary (`rontolisp:then` / `promisep`, and the interpreter's
`LispPromise`) is DELETED -- the names are no longer exported from the
rontolisp package; a future is the one asynchronous value and composition is
async-defun/async-lambda + await.

## The cross-backend contract

- **Eager start**: calling an async function runs the body immediately until its
  first await of an UNSETTLED future (or completion); only then does the caller
  resume with the future. Output before the first suspension is identically
  ordered on every backend (what the ci-spec case pins).
- **await placement is lexical**: legal inside async-defun/async-lambda bodies
  and at top level (implicitly async); illegal in any plain defun/lambda, even
  nested inside an async body (the JavaScript rule). Checked by
  `am.ik.rontolisp.LispAsync` on the resolved AST -- every backend runs
  `checkTopLevel` (compilers) or the memoized per-form check (interpreter, in
  `checkAwaitPlacement`, which also PRE-APPROVES the `%async-run` thunk lambdas
  the lowering synthesizes -- without that, evaluating the lowered thunk as a
  lambda form would re-reject its awaits). Await of a settled future or a
  non-future never suspends; nested futures flatten; an errored future
  re-signals its condition AT AWAIT (handler-case around the await catches it
  by type).
- **Lowering**: `(async-defun name (ll) body...)` lowers to
  `(defun name (ll) (rontolisp::%async-run (lambda () body...)))`
  (`LispMacroExpander.expandAsyncDefun`; deep pre-pass
  `LispAsync.lowerProgram` on the compile paths, evalCons case on the
  interpreter). `%async-run` is the ONE backend primitive. The lambda-list stays
  on the outer defun, so `&optional` default init forms run synchronously at
  entry (LispAsync checks them in sync context).

## Per-backend implementation

- **Interpreter**: `eval/AsyncRuntime.run` = virtual thread + eager-start
  handoff latch (`releaseHandoffIfPending` is called by await before blocking).
  This is deliberately the ONLY LispEvaluator-reachable thread site; the browser
  playground substitutes it (`src/web/java/.../Target_AsyncRuntime.java`, body
  runs synchronously to completion -- async bodies do not overlap in the
  playground, fetch still does via the SAB broker). Values: `LispFuture` (wraps
  CompletableFuture) and `LispStream` (chunks + pending reads + close + fail;
  `fail` drains buffered chunks first). After the first suspension the body runs
  in REAL PARALLEL with the caller -- global state races are the user's business
  (documented divergence from the component backend's cooperative model).
- **JVM**: `JvmAsyncRuntimeBuilder` (hand-assembled). A future is a bare
  CompletableFuture (also the legacy then-chain carrier); a stream is
  `{SMARKER, LinkedBlockingQueue, AtomicInteger}` with the interned SMARKER
  string re-enqueued as the EOF poison pill; `stream-read` returns an
  `{RMARKER, queue, state}` token whose blocking take happens at `_await`. The
  generated class `implements Runnable` (instance fields `_asyncFn` /
  `_asyncFuture` / `_asyncLatch`), `_async_run` spawns `Thread.ofVirtual()`.
  An error in the body CANNOT ride the `_condTl` ThreadLocal across threads, so
  `run()` completes the future NORMALLY with `{EMARKER, throwable, condition}`
  and `_await` re-sets `_condTl` on the awaiting thread before rethrowing --
  that is what makes handler-case dispatch conditions across the await.
  `_handoffTl` shares the `<clinit>` with `_condTl`. Opaque prints: `#<FUTURE>`
  (CompletableFuture or read token), `#<STREAM>`.
- **Preview-1 wasm-GC**: degenerate synchronous. `%async-run`
  (`WasmAsyncRunCompiler`) calls the thunk through dispatch-0 and wraps the
  value in a settled kind-2 `TYPE_PROMISE` -- the struct is KEPT as P1's
  internal degenerate-future representation (its former producer,
  `rontolisp:then`, is gone); `_promise_await` (`FUNC_PROMISE_AWAIT`) is P1's
  await resolver, and `futurep` ref.tests it. Streams are a compile error.
  CAVEAT: an async body's ERROR signals at the CALL, not at await (eager
  run-to-completion) -- observably identical when the await is adjacent.
- **--component (asyncMode)**: async-defun/async-lambda (and a top level with
  awaits) compile as ENTRY+RESUME state machines over first-class
  `TYPE_FUTURE`s (`WasmAsyncEmit`, `.todo/139` Phase 7). The import layer is a
  real scheduler (Phase 8): an `async func` wit-import member returns a
  pending `TYPE_FUTURE` through `rontolisp::%subtask-future` (registry +
  per-task waitable-set), and the blocking event loop `_sched_loop`
  (`WasmFutureRuntimeBuilder`) drives suspensions from the synchronous
  boundaries (the top-level `_start` entry, and a wasm-export wrapper whose
  async target suspended -- the fetch-inside-serve case). Tasks are
  cooperative and single-threaded; blocking `waitable-set.wait` is base
  component-model-async (wasmCloud-legal, see `.kb/wasi-component.md`).
  asyncMode FORCES EH mode, so an async component needs
  `wasmtime -W exceptions=y`. Component streams: `TYPE_WASI_STREAM
  {eof, readFn, closeFn}` wraps the wasi-backed body streams http.lisp
  produces (`rontolisp::%wasi-stream-new` over two arity-0 Lisp thunks; the
  close protocol lives in http.lisp, runs once at EOF or stream-close);
  stream-read returns settled futures (the underlying built-in still blocks
  the task while a chunk is in flight -- the pending-future upgrade is the
  remaining true-concurrency item); guest `make-stream`/`stream-write` stay
  compile errors.
- **--no-gc**: the whole async surface is rejected by name with
  "... is not supported with --no-gc (use the default GC backend)".
- **wait-for**: interpreter = `AsyncRuntime.timer` and JVM = `_wait_for`, both
  `new CompletableFuture().completeOnTimeout(nil, ms, MILLISECONDS)` (the JDK's
  shared delayer -- no new thread site; the Web Image substitution settles
  immediately). BOTH wasm backends reject it at compile time (no host timer
  wired yet; the component lowering to `wasi:clocks/monotonic-clock@0.3.0`'s
  own `wait-for` is recorded in `.todo/139`).

## read-all is prelude Lisp

`rontolisp:read-all` is an `async-defun` in `LispPreludeLibrary` (ONE
definition for every backend), lazy-loaded on the interpreter and spliced on
the compile paths -- which is why compiler tests that use it must mirror the
CLI's `LispPreludeLibrary.process` pre-pass (JvmLispCompilerTest's
compileAndRun does).

## http-handler interaction

A handler that awaits (fetch inside serve) must itself be an async-defun; the
servers await the handler's future (interpreter `invokeHttpHandler`, the JVM
generated `handle()`, http.lisp's `%serve-handle` -- itself an async-defun,
recognized by `HttpLibrary.defunName` for the splice reachability walk). The
request `:body` is an asynchronous stream ON EVERY BACKEND (the component
wraps the wasi request body; the interpreter/JVM buffer into one settled
chunk); a stream response `:body` is drained before sending (buffered
transport v1; the component's `%serve-handle` drains via its private
`%http-drain` -- http.lisp must stay self-contained, so it does NOT call the
prelude's read-all).
