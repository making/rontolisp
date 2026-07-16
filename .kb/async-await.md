# async/await: async-defun, await, futures and asynchronous streams

The user surface is `rontolisp:async-defun` / `rontolisp:async-lambda` (defining forms),
`rontolisp:await` (a SPECIAL FORM), the predicates `rontolisp:futurep` /
`rontolisp:streamp`, the stream operations `rontolisp:make-stream` /
`stream-read` / `stream-write` / `stream-close` / `read-all`, and the timer
`rontolisp:wait-for` (milliseconds -> a future settling to nil; deliberately
NOT named sleep -- `cl:sleep` exists with blocking-seconds semantics). The
legacy promise vocabulary (`rontolisp:then` / `promisep`) still exists but is
transitional; new code (and the docs) use futures.

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
  value in a settled kind-2 `TYPE_PROMISE`; `_promise_await`'s kind-2 branch
  recursively awaits (nested-future flattening). Streams are a compile error
  ("asynchronous streams are not available on the WASM backends yet").
  CAVEAT: an async body's ERROR signals at the CALL, not at await (eager
  run-to-completion) -- observably identical when the await is adjacent.
- **--component**: same degenerate `%async-run` for now; the awaits inside the
  body block the component's task via the parked `waitable-set.wait`
  (see `.kb/wasi-component.md` -- all base component-model-async, wasmCloud
  hosts it). True cooperative concurrency (state-machine compilation of async
  bodies + a guest event loop) is the planned next step (`.todo/139`).
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
request `:body` is an asynchronous stream (one settled chunk today); a stream
response `:body` is drained before sending (buffered transport v1).
