# True async/await: callback-ABI cutover + first-class future/stream

Replace the stackful/sync-builtin WASI 0.3 async implementation with the CORE
callback-based component-model-async ABI only, and redesign the language surface
around explicit `rontolisp:async-defun` / `async-lambda` / `await` with
first-class `future` / `stream` values (promise/then/promisep deleted, breaking
change accepted). Goal: every rontolisp component runs on wasmCloud (which
enables only `component-model-async` + gc + exception-handling and can never
enable 🚝 more-async-builtins / 🚟 async-stackful), and the run flags shrink to
`wasmtime run/serve -W gc=y -W exceptions=y [-S http=y]`.

Full plan: `~/.claude/plans/rontolisp-wasi-p3-more-async-builtins-wa-idempotent-honey.md`
(user-approved 2026-07-16). Phases: 0 spike (DONE) / 1 shared frontend /
2 interpreter / 3 JVM / 4 P1 degenerate + --no-gc errors / 5 ComponentWriter
encoders / 6 callback substrate + adapter rewrite / 7 state-machine compilation /
8 import layer + scheduler / 9 http.lisp + concurrent serve / 10 cleanup+docs+E2E.

## User-approved language decisions

1. Explicit coloring: `async-defun`/`async-lambda`; `await` legal only inside
   them and at top level (top level implicitly async).
2. Vocabulary: future/stream (WIT-aligned); promise/then/promisep DELETED.
3. `(await (fetch url))` -> `(:status <int> :headers <alist> :body <stream>)`;
   `(await (read-all s))` drains. `stream-read` future settles to chunk or nil=EOF.
4. EH x await fully supported in v1 (state machine re-enters try_table per state);
   the one restriction: await inside an unwind-protect CLEANUP form = compile error.
5. eager-start semantics: body runs synchronously to first unsettled await;
   `await` of settled/non-future never suspends; await flattens nested futures;
   errored future re-signals its condition at await (memoized).
6. Concurrency contract: interpreter/JVM = real parallelism on virtual threads
   after first suspension; component = cooperative single-threaded; P1 =
   degenerate synchronous (everything settles immediately, streams = compile
   error); --no-gc = compile error for async forms.

## Phase 0 spike results (DONE 2026-07-16, all on wasmtime 46.0.1 + wasm-tools 1.252)

Artifacts: session scratchpad `spike-callback/` (cb-spike.wast, gc-eh-cb.wast,
stdout-probe.wat, stdin-probe.wat, bytes.wat + downloaded wasmtime 46 wast suite).
All pass under `wasmtime wast -W component-model-async=y` (or NO flags: cm-async
is DEFAULT-ON in wasmtime 46; `wasmtime run` needed zero flags for the probes).
Validation: `wasm-tools validate -f component-model,cm-async` ONLY.

### Byte encodings (derived via wasm-tools dump, pin in ComponentWriterTest)

- Async variants of the stream/future builtins = the SYNC encoders + canonical
  option `0x06` (Async) in the options vector:
  `stream.read` = `0f <ty> 02 06 03 <mem>`-style (`[Async, Memory]`),
  `stream.write` = `10`, `future.read` = `16`, `future.write` = `17`.
  (Options actually dumped as `02 06` count=2 Async, then `03 <mem>`? NO --
  dump shows `0f 00 02 06 03 00` = tag, ty, optcount=2, 0x06 Async, 0x03+idx
  Memory. Memory option tag is 0x03 as in existing encoders. Verify against
  existing canonStreamRead pin when writing the encoder.)
- Callback lift = ordinary `canon lift` byte `00 00 <corefunc> <optcount>`
  with options `[Memory(0x03 idx), UTF8(0x00), Async(0x06), Callback(0x07 <corefunc>)]`
  then the (async, tag 0x43) component func type index. Callback option tag = 0x07.
- `context.get i32 slot` = `0a 7f <slot>`; `context.set` = `0b 7f <slot>`.
- `waitable-set.poll` = `21 00 <mem>` (0x00 = cancellable:false).
- `stream.cancel-read` = `11 <ty> 00`; `future.cancel-read` = `18 <ty> 00`.
- Existing encoders staying: task.return 0x09, waitable-set.new 0x1f /
  .drop 0x22, waitable.join 0x23, subtask.drop 0x0d, async lower option 0x06,
  stream/future.new + drop-readable/-writable.

### Callback protocol constants (host-verified)

- Core export sig under callback lift: `[flat params] -> [i32 packed-code]`;
  callback sig `(i32 event, i32 waitable-index, i32 payload) -> i32 packed-code`.
- Packed codes: EXIT=0, YIELD=1, WAIT = 2 | (waitable-set << 4), POLL = 3 | (set << 4).
- Events: EVENT_SUBTASK=1, EVENT_STREAM_READ=2, EVENT_STREAM_WRITE=3
  (FUTURE_READ=4 / FUTURE_WRITE=5 per spec ordering -- pin when first used).
- Async stream/future op result: BLOCKED = -1 (0xFFFFFFFF), else
  `(amount << 4) | status` with COMPLETED=0, DROPPED=1 (CANCELLED=2).
  DROPPED with amount 0 on a read = EOF.
- Async-lowered import call: `(subtask << 4) | status`, STARTING=0, STARTED=1,
  RETURNED=2 (RETURNED arrives with subtask 0 when the callee completed eagerly
  via task.return before its first suspension -- no waitable to track).
- `stream.new` returns i64: readable = low 32, writable = high 32.
- task.return / result layouts unchanged from the stackful era (.todo/02 notes).

### Behavioral findings (all load-bearing for the design)

1. **Blocking `waitable-set.wait` is BASE cm-async, not 🚝** (the 🚝 gate covers
   only the sync stream/future builtin variants -- wasmCloud's rejection message
   confirmed this and wast sync-streams.wast parse-fails without the flag while
   partial-stream-copies.wast passes). AND it is legal from a callback-lifted
   task -- both during the initial call and INSIDE a callback invocation
   (cb-spike.wast cases (i)/(ii)). Consequence: synchronous Lisp code can do
   async-op + blocking-wait-loop for I/O; no trap floor, no EAGAIN protocol.
   Only a SYNC-lifted (0x40) export may not block before returning
   ("cannot block a synchronous task before returning").
2. **stdout write-via-stream + async stream.write COMPLETEs synchronously** on
   wasmtime (stdout-probe: "C"); the write-via-stream result future resolves ok
   after drop-writable. One persistent stream per fd works.
3. **stdin async read starts BLOCKED even for piped input** (stdin-probe:
   "B:hi-from-pipe"); blocking wait resolves it. Closed stdin = DROPPED/0 = EOF
   ("E:"). So fd_read(stdin) from sync code = async read + blocking wait, and in
   async context it becomes a real suspension point (Phase 10 upgrade).
4. **GC + EH + callback lift coexist** (gc-eh-cb.wast): GC struct state survives
   suspension; try_table catch works before suspension and inside the callback.
5. **context.get/set (2 i32 slots per task) persists from initial call into the
   callback of the same task**; available under base cm-async.
6. **Intra-component numeric streams work** (u64 proven; non-numeric payloads
   trap "cannot read from and write to intra-component future/stream with
   non-numeric payload"): a pending read completed by a same-task write queues a
   deliverable event -> this is the **per-task doorbell** primitive.
7. **wasmCloud main's engine exposes exactly wasm_component_model_async(true),
   gc, wasm_exceptions** (wash-runtime/src/engine/mod.rs WasmProposal) -- our
   target feature set matches; wash 2.5.2 installed locally for the Phase 6/9
   empirical check (`dev.wasm_proposals: [gc, exception-handling, component-model-async]`).
8. Current `run` export is ALREADY an async-typed (0x43) SYNC-ABI lift (plain
   canon lift, blocks legally); only serve's `handle` uses the true stackful
   lift (async canon option). The 🚝 dependency (sync builtins in adapter.wat +
   http.lisp glue + emitAsyncAwaitBody spin) is what locks wasmCloud out today.

### Scheduler design notes fixed by the spike

- **One waitable-set per TASK** (not per instance): events must be delivered to
  the callback of the task that owns the pending op, because task.return and
  context are per-task. A host-backed future's waitable joins the set of the
  task that started the op.
- **Cross-task wakeup = per-task doorbell**: when task A settles a guest future
  that task B's frame awaits, A cannot resume B's frame in A's callback context
  (wrong task for task.return). Instead every task owns an intra-component u64
  doorbell stream with a standing pending read joined into its set; A writes 1
  to B's doorbell-writable (completes immediately, finding 6) and B's callback
  fires, B re-arms the doorbell read and resumes its own frames.
- Blocking sync-code I/O inside an async task parks that task's fiber only;
  other tasks keep running (empirically: cross-instance in cb-spike; verify
  intra-instance interleaving with the Phase 9 concurrent-serve test).

## Status

- Phase 0: DONE (spike results above).
- Phase 1 DONE (suite 3694/0): LispNames (+ *_QUALIFIED, ASYNC_STREAMP because
  cl:streamp exists -- Lisp symbol stays "streamp", packages disambiguate),
  PackageRegistry rontolisp exports, `LispAsync` placement checker (knows the
  %async-run thunk-lambda so the lowering's inner lambda is walked in async
  context; lambda-list init forms are sync), `LispMacroExpander.expandAsyncDefun/
  Lambda` -> `(defun name (ll) (rontolisp::%async-run (lambda () body)))`,
  `LispFuture` (CompletableFuture + settled/failed), `LispStream` (chunks +
  pendingReads + close + fail w/ buffered-drain-first), LispVal permits.
- Phase 2 DONE (suite 3714/0, -Pweb compile OK, AsyncEvalTest = 20 specs):
  eval/AsyncRuntime (vthread + eager-start handoff latch; THE only
  evaluator-reachable thread site) + web Target_AsyncRuntime (sync-to-completion);
  evalCons ASYNC_DEFUN/ASYNC_LAMBDA/AWAIT cases (await = special form; function
  registration REMOVED); %async-run registered in LispEvaluator (needs apply);
  awaitValue: futures flatten in a loop, LispEvalException crosses intact
  (condition preserved), promise legacy path retained until deletion;
  checkAwaitPlacement memoized by identity + pre-approves %async-run thunks
  (evalLambdaForm would otherwise reject the lowered thunk); HttpSupport
  rewritten to ofPublisher streaming (BodyPump w/ UTF-8 carry, LispStream.fail
  on transport error) + Start record; Target_HttpSupport -> one-chunk settled
  stream; fetch -> LispFuture w/ (:status :headers :body <stream>); Environment
  futurep/streamp/make-stream/stream-read/stream-write/stream-close;
  rontolisp:read-all = PRELUDE async-defun (LispPreludeLibrary, lazy-loaded;
  definesName also matches async-defun); interpreter http-handler: request
  :body = settled stream, handler future awaited, response stream body drained
  (transport stays buffered v1 -- HttpHandlerSupport.Handler interface
  UNCHANGED, true chunked transfer deferred); doc example
  doc/{en,ja}/compiling/wasm.md fetch-status flipped to async-defun; then/
  promisep still work (legacy) -- deletion deferred to Phase 10 with the docs.
- Phase 3 (JVM) implementation LANDED (suite verification pending): new
  `JvmAsyncRuntimeBuilder` (hand-assembled: _async_run/_await/_futurep/_streamp/
  _make_stream/_stream_read/_stream_write/_stream_close/_drain_body/
  _release_handoff + instance run()); JvmLispCompiler pre-pass (checkTopLevel ->
  UnsupportedOperationException + LispAsync.lowerProgram after flattenTopLevel);
  usesAsyncRuntime detection forces conditionChannel + Runnable interface +
  no-arg ctor + _handoffTl ThreadLocal (joined into the SAME <clinit> as
  _condTl) + _invoke_0/_invoke_1 dispatchers; JvmFetchRuntimeBuilder lost _await
  (kept _fetch, buffered ofString); _await's HttpResponse branch (fetch-gated)
  builds (:status :headers :body <one-chunk closed stream>); JvmExprCompiler
  dispatch via JvmAsyncOpsCompiler + async-defun/lambda fallback expansion;
  http-handler handle(): request :body = one-chunk stream (empty = closed-empty),
  result awaited, stream response body drained via _drain_body (maxLocals 19);
  print branches: #<FUTURE> (CompletableFuture + read token) / #<STREAM>
  (PromisePrint record extended). CLI smoke: interpreter and JVM outputs
  byte-identical incl. eager-start ordering, ping-pong rendezvous, EH-across-
  threads (EMARKER payload + _condTl re-set on the awaiting thread), prelude
  read-all splice. New JvmAsyncCompilerTest (10 specs); JvmLispCompilerTest
  fetch tests + HttpHandlerJvmTest echo updated to the new shapes.
- Phase 3 (JVM) design notes: lower async forms via a DEEP pre-pass
  (LispAsync.lowerAsyncForms, shared with P1) right after flattenTopLevel +
  LispAsync.checkTopLevel BEFORE it; %async-run = INVOKESTATIC _async_run via
  generated-class `implements Runnable` + instance fields + vthread + latch
  (class v50-legal); error crossing threads: run() catches Throwable and
  completes the future NORMALLY with {EMARKER, throwable, condTl.get()} -- _await
  re-sets _condTl on the awaiting thread and rethrows, so the catch-all
  handler-case + _condTl channel dispatches typed conditions (JVM EH uses
  catch-all + _condTl ThreadLocal, which does NOT cross threads by itself);
  streams = Object[]{SMARKER, LinkedBlockingQueue, AtomicInteger closed} with
  the SMARKER string as the re-enqueued EOF poison pill; stream-read returns an
  {RMARKER, stream} token; _await on the token releases handoff + q.take();
  futurep = instanceof CompletableFuture OR the RMARKER token; JVM fetch stays
  BUFFERED (ofString) v1 -- :body becomes a one-chunk closed stream (no
  Flow.Subscriber in hand-assembly); read-all rides the prelude splice; no
  BuiltinFunctionWrappers for the new names (then/promisep precedent).
- Phase 3 gates GREEN (full suite BUILD SUCCESS + -Pweb compile).
- Phase 4 (P1 degenerate + --no-gc errors) LANDED (suite verification pending):
  WasmLispCompiler got the same checkTopLevel+lowerProgram pre-pass (applies to
  BOTH P1 and --component: interim, %async-run on the whole wasm-GC family runs
  the body eagerly to completion and wraps a settled kind-2 TYPE_PROMISE --
  WasmAsyncRunCompiler; on component the body's awaits block the stackful task,
  so semantics hold degenerately until Phase 7 state machines); buildAwait's
  kind-2 branch now recursively awaits (nested-future flattening); futurep =
  promisep's ref.test; stream ops = clear compile error on the WASM backends;
  opaque print flipped to #<FUTURE> everywhere incl. interpreter LispPromise +
  ci-spec + docs annotations; NoGcWasmCompiler rejects the whole async surface
  by name with the house message. P1/component manual smokes byte-match the
  interpreter output. CAVEAT (degenerate mode): an async body ERROR on P1/
  component signals at the CALL, not at await (eager run-to-completion) --
  observably identical when the await is adjacent; document with the backends
  matrix in Phase 10.
- Phase 5 DONE: ComponentWriter gained canonLiftMemoryUtf8AsyncCallback (option
  tag 0x07 + cb corefunc), canonStream/FutureRead/WriteAsync (sync encoding +
  Async option 0x06), canonFutureReadAsync w/ realloc, canonFutureWriteAsyncUtf8,
  canonContextGet/Set (0a/0b 7f slot), canonWaitableSetPoll (21 00 mem),
  canonStream/FutureCancelRead (11/18 ty 00); golden pins in
  ComponentWriterTest.callbackAsyncAbiEncodings (26/26 green).
- Phase 6 LANDED, wasmCloud GREEN: the KEY spike consequence exploited --
  blocking waitable-set.wait is BASE cm-async and legal from async-typed AND
  callback-lifted tasks, so the whole flag-free cutover needed NO state machine:
  (a) adapter.wat + adapter-sockets.wat + adapter-http-server-p1.wat: sync
  stream/future built-ins -> async variants + $await_waitable blocking wrappers
  (per-adapter cached waitable-set; scratch 0x50090/0x500D0/0x50020) with
  UNCHANGED call sites; regen.sh rerun; (b) buildBase/buildSock bind the async
  canons + waitable trio appended at 22-24/33-35 (downstream core-func indices
  +3: run alias 25/36, appendUserImports start 25/36, appendFuncExports 26/37);
  (c) asyncCanon READ/WRITE -> async variants; waitable trio bound for
  interfaces with calls OR asyncs OR taskReturns (three condition sites:
  WasmLispCompiler slot loop, WasmComponentBuilder, serve builder -- NOTE the
  import-slot keys use "\\0" separators, the reason WasmLispCompiler greps as
  binary); (d) WasmComponentImportCompiler wrappers: async canon + BLOCKED ->
  emitBlockedWait (fresh set, join, wait loop, unjoin via join(h,0), set drop),
  Gen.waitOrdinals plumbed from WasmLispCompiler; (e) serve handle = CALLBACK
  lift (canonLiftMemoryUtf8AsyncCallback) against a stub callback exported by
  the p1 bridge ("async_cb", unreachable -- never invoked since blocking waits
  do the parking); handle core sig now [i32]->[i32 EXIT] (isServeHandle in
  WasmExportCompiler + wrapper functype special case); (f) http.lisp
  %serve-handle -> async-defun awaiting %serve-dispatch (handlers may be
  async-defun; HttpLibrary.defunName recognizes async-defun for the reachability
  splice); (g) componentSyncExportWithIoStillTraps test flipped: sync exports'
  I/O now WORKS when the host doesn't block (print completes synchronously).
  VERIFIED flag-free on wasmtime 46: hello/print/file/stdin (`-W gc=y [-W
  exceptions=y]`), serve round-trip (`wasmtime serve -W gc=y -W exceptions=y`),
  fetch->serve proxy (`wasmtime run -S http=y -W gc=y -W exceptions=y`), and
  **wasmCloud wash 2.5.2 `wash dev` SERVES the http-handler example**
  ("Hello from wasmCloud!") with dev.wasm_proposals [gc, exception-handling,
  component-model-async] (config updated in examples/wasmcloud/http-handler).
  Remaining for later phases: other examples' configs + README status flip,
  test-command flag reduction (they still pass the old flags, harmless).
- Phase 7 (component state machines + TYPE_FUTURE) LANDED 2026-07-17 (suite
  3745/0 pre-new-tests; integration battery added; native E2E pending below):
  - asyncMode = --component && program uses async-defun/async-lambda/await;
    FORCES ehMode (entry reject + rejected-await re-signal throw $lisp-cond), so
    an async component needs `-W exceptions=y` (serve/fetch docs already carry
    it). Programs without async surface are byte-identical (--simd-style gating:
    ASYNC_TYPE_COUNT=2 after the simd types, WasmFutureRuntimeBuilder FUNC_COUNT=6
    at asyncFuncBase() after the simd block; userFuncBase()/fixedTypeCount()
    account).
  - TYPE_FUTURE{mut state 0P/1F/2R, mut value, mut waiters, mut source} +
    TYPE_ASYNC_FRAME{mut state, mut spill, mut future, mut env} in ONE rec group
    (same-group membership keeps the two structurally identical structs distinct
    under wasm-GC structural canonicalization -- a separate group would merge
    them). Runtime: _future_new/_settle/_reject/_add_waiter/_wake/_poll; wake =
    direct dispatch_1 call of TYPE_CLOSURE{resumeFuncId, frame} waiters with a
    per-waiter try_table (completion settles frame.future -> cascade, uncaught
    condition rejects it); poll flattens settled chains, resolves legacy
    TYPE_PROMISE via _promise_await (blocking, Phase-8 seam), throws rejected
    payloads (memoized re-signal at await), returns pending futures unchanged.
  - async-defun = entry (the defun, public signature) + resume (arity-1
    LambdaInfo with PRECOMPILED bytes -- new nullable record component -- so the
    dispatch table can wake it). Resume slots: 0=frame, 1=unused resume value
    (landings re-poll the spilled future), 2=$rt i32 (own local-decl run),
    params/temps from 3 mirrored 1:1 by the spill array; prologue restores all +
    frame.env -> closureEnvSlot local + boxes captured params at state 0 only.
    Suspension = plain wasm `return frame` (unforgeable sentinel, ref.eq'd by
    entry/wake) after frame.state=k, unrolled local spill, add_waiter, and one
    eh-depth decrement per enclosing handler-case protected region. asyncSpine
    flag = the spine-position safety check (await elsewhere -> clear error).
  - Structure dispatch: contiguous state ranges from WasmAwaitAnalysis.countAwaits
    (mirrors LispAsync.check traversal; per-region assertStates catches
    expansion-duplicated awaits). Guarded sequences (progn/let body/%block/defun
    body/while body/setq pairs), if (test+branch ranges; test awaits SUPPORTED),
    while (test awaits supported too), let inits (skip on resume; locals come
    back from the spill; name binds after init = let semantics), handler-case
    protected form (statement guard routes INTO the try_table; head depth++
    re-runs on resume, suspend undid it), unwind-protect protected form
    (suspend = return -> cleanups naturally skipped, re-armed on re-entry).
  - A-normalization is at the COMPILE SITE, not a pre-pass: compileCons hoists
    strict-call arguments containing awaits into let* (%await$N; literals stay);
    denylist = specialOperatorNames() + %-heads + rontolisp/usocket directive
    macros, so it also normalizes macro EXPANSIONS.
  - v1 compile errors: await in handler-case CLAUSE bodies / :no-error, in
    unwind-protect cleanups (user decision), special-var let around awaits,
    nested async-defun (use async-lambda). async-lambda = entry+resume lambda
    pair; captures ride frame.env.
  - Top level with awaits = implicit async pair; _start entry runs it eagerly,
    SUSPENSION TRAPS (unreachable -- nothing can produce a pending top-level
    future until the Phase-8 event loop); uncaught rejection = catch-all trap as
    today. Pass 2b unchanged when the top level has no awaits.
  - wasm-export of an async defun: wrapper polls the returned future (serve's
    handle drops the settled nil; a rejection traps through the wrapper
    catch-all = today's error-in-handler shape). serve verified E2E on wasmtime
    46 (`wasmtime serve -W gc=y -W exceptions=y`, %serve-handle now a state
    machine).
  - Internal TEST primitives (undocumented, asyncMode only):
    rontolisp::%future-new/%future-settle/%future-reject let the integration
    battery drive REAL suspensions before Phase 8: spill/restore, waiter
    cascade, handler-case catch ACROSS a suspension, unwind-protect re-arm,
    loop/while-test awaits, async-lambda captures -- all green on wasmtime 46.
  - futurep = TYPE_PROMISE || TYPE_FUTURE ref.test; print/princ gained a
    TYPE_FUTURE "#<FUTURE>" branch (parameterized builders, -1 = absent).
  - Eager 4-backend parity verified manually (interp/JVM/P1/component identical
    incl. handler-case around rejected await). KNOWN SEAM: rontolisp:then on a
    TYPE_FUTURE passes the future to the callback unresolved (then is legacy,
    deleted in Phase 10).
  - DEFERRED to the final phase (user instruction 2026-07-17: "E2E is slow, run
    it in the last phase"): native-image CiSpecE2eTest (ci-spec async case
    expected outputs are unchanged, only the component implementation moved to
    state machines -- still MUST run before push per CLAUDE.md), wasmCloud wash
    re-verify (serve components now carry the async runtime; feature set
    unchanged), docs note that async components need `-W exceptions=y` (Phase 10
    docs pass).
- Phase 8 (import layer + scheduler) LANDED 2026-07-17 (full suite BUILD
  SUCCESS incl. Docker; wasmtime 46 smokes green; uncommitted):
  - `WasmComponentImportCompiler`: `emitAsyncAwaitBody` (blocking spin) DELETED;
    the second wrapper of an async call is now a LIFT-ONLY wrapper
    (`%member-lift`, `buildAsyncLiftBody` -- token cons in, result lifted from
    the retptr, no waiting, no drops; AsyncCall.awaitName renamed liftName).
    The async BUILT-IN wrappers (stream/future read/write) KEEP emitBlockedWait
    (own fresh set, unjoin after) -- flipping them to pending futures is the
    Phase 9 stream item.
  - `WasmFutureRuntimeBuilder` FUNC_COUNT 6->8: OFF_SUBTASK_FUTURE=6
    (`_subtask_future(token, lift) -> future`: RETURNED-eagerly -> dispatch_1
    lift -> settled struct; else pending future + registry push + lazy
    task-set create + waitable.join) and OFF_SCHED_LOOP=7 (`_sched_loop(fut) ->
    value`: blocking waitable-set.wait loop; EVENT_SUBTASK/RETURNED -> unlink
    registry entry by waitable, dispatch_1 lift, subtask.drop, _future_settle
    (cascade wakes waiters); exits via _future_poll so a rejection re-signals).
    Bodies embed placeholder-ordinal calls (WasmImportInjector resolves them);
    `Sched` record carries (WaitOrdinals of the FIRST async-calling interface
    -- any trio works, they alias the same built-ins --, registry global,
    set global, __ronto_alloc index); null Sched -> unreachable stubs.
  - 2 new asyncMode-only globals after ehDepth: scheduler registry (ref null
    eq, cons list of (subtask . (future . (lift . token)))) and the task
    waitable-set handle (i32, 0 = not created).
  - `WasmAsyncEmit.emitStartEntry`: the top-level suspension `unreachable` is
    now `_sched_loop(frame.future)` -- the top level drives real pending
    futures to completion (deadlock with an empty set traps).
  - `WasmExportCompiler`: an async-defun export target's wrapper polls, and a
    STILL-PENDING future is driven through `_sched_loop` -- this is what makes
    fetch-inside-a-served-handler work (the handler suspends, the synchronous
    handle boundary drives it; still sequential per task, real callback
    concurrency = Phase 9).
  - `rontolisp::%subtask-future` internal (LispNames SUBTASK_FUTURE_INTERNAL
    [+_QUALIFIED], WasmFutureInternalCompiler case, counted into usesAsync so
    a never-awaiting program still gets the runtime).
  - `WitImportDirective`: thenDefun/awaitUnwrapDefun DELETED. An async func
    member now binds: component non-result = plain defun returning
    `(%subtask-future (start p...) #'lift)`; component result-returning =
    `(rontolisp:async-defun name (p...) (let ((%wit-envelope (await
    (%subtask-future ...)))) (rontolisp::%wit-result %wit-envelope)))` (await
    in a let init = spine position, deliberately NOT relying on the
    normalizer); interp/JVM = `(rontolisp:async-defun name (p...) (%wit-call
    ...))` (settled/rejected future, futurep parity); P1 unchanged (degenerate
    sync, documented).
  - http.lisp: `rontolisp:then` composition replaced by `%fetch-run`
    (async-defun awaiting the send future) + fetch keeping the
    nil-on-start-failure contract; %http-client:send now returns a first-class
    future.
  - Verified on wasmtime 46 (`-S http=y -W gc=y -W exceptions=y`): fetch
    round-trip, 2-in-flight reverse-order await, transport-error rejection
    caught by handler-case at await THEN a subsequent fetch succeeding, plain
    serve, and the fetch-in-serve proxy (real suspension driven by the export
    wrapper). componentFetchOverHttp's legacy then-chain assertion removed
    (then dies in Phase 10).
  - DEFERRED from Phase 8: wasi:clocks monotonic-clock `wait-for` on component
    (needs import-block.bin regeneration -- the block's monotonic-clock
    instance type declares `now` only, no `wait-for`; regen shifts the block's
    type indices, so every T_* in WasmComponentBuilder must be re-derived via
    wasm-tools dump and the WIT fixtures regenerated); interpreter fetch
    transport errors are a plain error, NOT rontolisp:wit-error (pre-existing
    divergence, noted for the Phase 10 docs).
- Phase 9 (component streams + serve) IN PROGRESS 2026-07-17 (uncommitted):
  - `TYPE_WASI_STREAM {mut i32 eof, mut readFn, mut closeFn}` joined the async
    rec group (ASYNC_TYPE_COUNT 2->3; Ctx.wasiStreamTypeIndex threaded incl.
    WasmAsyncEmit.freshCtx); the two closures are arity-0 Lisp lambdas
    (asyncMode adds dispatch_0 to indirectCallArities), so the close PROTOCOL
    lives in http.lisp, not codegen.
  - WasmFutureRuntimeBuilder FUNC_COUNT 8->10: OFF_WSTREAM_READ (settled
    future of the next chunk; first nil flips eof + runs closeFn once) and
    OFF_WSTREAM_CLOSE (idempotent). Reads still BLOCK the task while a chunk
    is in flight (settled futures) -- the pending-future upgrade of the async
    built-in wrappers is the remaining true-concurrency item.
  - `rontolisp::%wasi-stream-new` internal (LispNames, WasmFutureInternal
    Compiler); streamp/stream-read/stream-close compile on --component in
    asyncMode (WasmWasiStreamCompiler); make-stream/stream-write keep a clear
    error; print/princ gained a lazily-added "#<STREAM>" branch (the string
    joins the table only in async modules -- byte-identity).
  - http.lisp: %http-read-all/%http-consume-text -> %http-body-value (wraps
    consume-body's stream+trailers+transmit protocol into the stream value);
    fetch :body AND serve request :body are now first-class streams on EVERY
    backend (the user-approved decision 3 -- component finally matches the
    interpreter/JVM); %serve-handle drains a STREAM response body (a proxied
    fetch :body passes straight through) via prelude read-all, and
    stream-closes the request body after dispatch (idempotent).
  - Tests updated to the stream :body contract: componentFetchOverHttp /
    WithRuntimeBuiltUrls / InsideAsyncExport (the last also fixed: its await
    sat in a PLAIN defun, which LispAsync rejects since Phase 1 -- env-gated,
    so nobody had run it), fetch-in-serve proxy, the wit-user-program echo
    backend; WasmLispCompilerTest.serveProgram now splices the prelude
    (http.lisp references read-all). Examples cut over: wasmcloud
    http-handler/http-kv-handler/service-tcp + net magic-8-ball/httpbin/
    linalg-api use a `route` + async-defun `handle` body-drain wrapper
    (`(append (list :body body) request)` shadows :body); dog-fetcher and
    http-client COLLAPSED their #+rontolisp-wasm body split (streams exist on
    wasm now).
  - Verified on wasmtime 46: fetch :body stream round-trip byte-identical to
    the interpreter (incl. re-drain -> ""), serve request-body echo, GET empty
    body, upstream-fetch stream passthrough as response body, and 3
    OVERLAPPING curls against the fetch-proxy handler (concurrent serve, one
    instance per request).
  - FOUND + FIXED a pre-existing native-image break: resource-config.json
    still declared fetch.lisp/fetch.wit under FetchLibrary (deleted in
    .todo/02 Phase 3) and never declared wit.lisp -- so the NATIVE binary
    could not compile ANY fetch/serve/wit-import program ("http.lisp is
    missing from the classpath"; `wash dev` builds through the native binary,
    which is how it surfaced). Now http.lisp/http.wit under HttpLibrary +
    wit.lisp under WitLibrary.
  - wasmCloud `wash dev` E2E GREEN 2026-07-17 (examples/wasmcloud/http-handler:
    GET / + /api/greet + the POST /api/echo stream-drain route all answer;
    build ran through the rebuilt native binary). NOTE: /usr/local/bin/
    rontolisp is Gatekeeper-SIGKILLed after an in-place `cp` over the old
    signed binary (`codesign` cannot re-sign in the root-owned dir); wash was
    verified via a PATH shim to target/rontolisp -- the user should reinstall
    the binary properly (rm + cp needs sudo).
  - Concurrent serve verified manually: 3 overlapping curls against the
    fetch-proxy handler under `wasmtime serve` all completed with the upstream
    body (one instance per request; the export wrapper's _sched_loop drives
    each instance's suspension).
- Phase 9 DONE (full suite + Docker BUILD SUCCESS after the %http-drain fix +
  test-helper prelude splice; the earlier read-all coupling was removed --
  http.lisp is self-contained again).
- Phase 10 LANDED 2026-07-17 (uncommitted), scope-adjusted from the plan:
  - DELETED: `rontolisp:then` + `rontolisp:promisep` (LispNames constants,
    PackageRegistry exports, PackageIntrospection list -- so `list-functions`
    output changed --, Environment registrations, evaluator LispPromise
    self-eval + awaitValue promise tail, `LispPromise.java` + LispVal permits,
    `JvmThenCompiler`/`JvmPromisepCompiler` + JvmExprCompiler dispatch +
    JvmLispCompiler thenQualified detection, `WasmThenCompiler` + WasmExpr
    dispatch, `_future_poll`'s TYPE_PROMISE branch -- unreachable in asyncMode
    once then was gone), `.todo/45` (superseded: rejection handling =
    handler-case around await). `rontolisp:then/promisep` now fail at package
    resolution ("not external in the rontolisp package").
  - KEPT AS INTERNAL (deliberate deviation from the plan's deletion list, all
    documented in .kb): `TYPE_PROMISE` + `WasmPromiseRuntimeBuilder` +
    `FUNC_PROMISE_AWAIT` = P1's degenerate-future representation (%async-run
    producer; a rename would be cosmetic churn); the JVM `_await`'s MARKER
    then-chain branch (dead code inside hand-assembled bytecode -- removing it
    risks the v50 verifier lesson; a later mechanical pass);
    `canonWaitableSetWait` and the sync stream/future encoders that adapters
    still bind... actually the ADAPTERS bind the ASYNC variants since Phase 6;
    the sync encoders + stackful `canonLiftMemoryUtf8Async` in ComponentWriter
    are now likely dead -- grep + golden cleanup left for a later pass.
  - Docs (en+ja, mirrored): then/promisep pages + catalog entries + curated
    table rows deleted; http-fetch guide rewritten around
    async-defun/await/futurep and the everywhere-stream :body; fetch /
    http-handler reference pages (request `:body <stream>` + async-defun
    requirement, stream response bodies); the 6 stream pages' backend notes
    (component supports the fetch/serve body streams; make-stream/stream-write
    stay interpreter/JVM); async-defun component note = state machines + event
    loop + `-W exceptions=y`; packages/list-functions/eval-limitations/
    compiling wasm+jvm mentions. DocExamplesTest 480/0.
  - ci-spec: `promise-generic-await-then-promisep` ->
    `await-passes-non-futures-through`; list-functions expected updated.
  - CLAUDE.md: async invariant line rewritten (state machines, EH-mode force,
    streams everywhere); manual verify command 4 lost the stale
    `-W component-model-more-async-builtins=y`.
  - .kb/async-await.md + .kb/fetch-http.md rewritten for the new tiers.
  - native resource-config fix (see Phase 9) + native rebuilt.
- FOUND + FIXED during the (previously deferred) native CiSpecE2eTest: a
  PHASE-7 scaling cliff -- the implicit top-level async pair compiled the
  WHOLE top level into ONE resume function; for the concatenated ci-spec
  corpus that was a ~110k-instruction function with 1300+ locals, and
  CRANELIFT's compile time explodes superlinearly on it (the "hang" was
  wasmtime still compiling: main thread parked in CompileInputs::compile /
  rayon, RSS ballooning to tens of GB; the guest never started; `wasmtime
  compile -W gc=y` timed the halves at 13s -> 31s for +5 corpus cases).
  Diagnosed via `sample` of the wasmtime process (guest profiling pointed the
  wrong way). FIX: `WasmAsyncEmit.compileTopLevelChunkedProgn` -- the
  OUTERMOST top-level resume body outlines every await-free run of statements
  into its own plain arity-0 lambda-table function (`_toplevel_chunk_N`,
  called DIRECTLY with a nil env under a single `$rt == 0` guard; ctx keeps
  topLevel/usesEval so eval-global mirroring still works inside a chunk), so
  the resume keeps only the await statements + one call per chunk. Nested
  guarded bodies (a top-level let's) are NEVER outlined
  (AsyncResume.topLevelChunked one-shot flag) -- they may reference enclosing
  locals, which a chunk cannot see (the first cut broke exactly that,
  componentAsyncDefunCompilesAsStateMachine). Full corpus component run:
  never-finished -> 1.1 s. Suite + Docker green after the fix.
- FINAL GATES all green 2026-07-17 (uncommitted): full suite 3749/0 incl.
  Docker; native CiSpecE2eTest 824/0 across all four backends (5 s -- the
  component corpus that previously never finished); `-Pweb compile`; javadoc
  (Version-only, the allowed exception); DocExamplesTest 480/0; wasmCloud
  `wash dev`; manual 4-backend smoke byte-identical (futurep/await).
## NEXT (user request 2026-07-17, start a fresh session here): the `async` macro

Add `rontolisp:async` -- a WRAPPER macro that turns the ordinary defining
forms into their asynchronous counterparts, for a notation closer to
JavaScript's `async function` / `async (…) =>`:

- `(rontolisp:async (defun f (x) body...))` == `(rontolisp:async-defun f (x) body...)`
- `(rontolisp:async (lambda (x) body...))` == `(rontolisp:async-lambda (x) body...)`
- anything else inside = a clear compile/eval error naming what `async`
  accepts (a defun or a lambda form).

Design notes for the implementer:
- This is a pure FRONTEND rewrite -- expand it EVERYWHERE BEFORE the async
  machinery looks at the program, so `LispAsync.checkTopLevel`,
  `rewriteTopLevelAsyncDefuns` (WasmLispCompiler), `LispAsync.lowerProgram`
  (interp/JVM/P1) and `HttpLibrary.defunName`-style definition scanners only
  ever see async-defun/async-lambda. The natural home is
  `LispMacroExpander` (`expandAsync`), wired like the existing shared macros:
  LispNames + PackageRegistry rontolisp exports (+ PackageIntrospection?
  decide: async is a MACRO/special form, so it belongs in list-macros or
  list-special-forms, NOT list-functions -- ci-spec + doc page expectations
  change accordingly), evalCons case, JvmExprCompiler + WasmExprCompiler
  cases -- BUT a call-position-only expansion is NOT enough: a top-level
  `(async (defun ...))` must be rewritten before Pass 1 defun collection, so
  hook the TOP-LEVEL rewrite where flattenTopLevel/expandTopLevelDefinitions
  run (all three compilers + the evaluator's top-level path + UserMacroExpander
  so macro-generated `(async (defun ...))` works too).
- await placement: after expansion the existing checker just works; add a
  test that `(async (lambda ...))` bodies may await and a plain nested lambda
  inside still may not.
- Keep async-defun/async-lambda as the canonical lowered forms (docs may call
  `async` the sugar); no backend codegen changes at all.
- Tests: AsyncEvalTest + JvmAsyncCompilerTest + WasmLispCompilerIntegrationTest
  (component state machine through the sugar) + a ci-spec case; docs en+ja:
  new special-forms/rontolisp-async.md page + catalog + curated table +
  cross-links from async-defun/async-lambda pages.

- Phase 10 REMAINING (deferred, in priority order):
  1. native CiSpecE2eTest against the rebuilt binary (ci-spec changed -- MUST
     run before push; the binary at target/rontolisp is current except for the
     then/promisep deletion, so REBUILD FIRST).
  2. sockets/stdin async promotion (tcp-accept/recv + read-line as implicit
     suspension points in async context) -- an enhancement; blocking behavior
     is correct-if-sequential today.
  3. wait-for on --component (wasi:clocks monotonic-clock wait-for; needs
     import-block regen + T_* re-derivation, see the Phase 8 deferral note).
  4. Pending-future stream reads (async built-in wrappers returning pending
     TYPE_FUTUREs + EVENT_STREAM_* dispatch in the scheduler) and the REAL
     serve callback (per-task tables + context slot + doorbells, spike
     findings 5/6) -- true intra-instance concurrency; hosts re-instantiate
     per request today so this is unobservable until a host reuses instances.
  5. Sync stream/future encoder + stackful-lift encoder deletion in
     am.ik.wasm.ComponentWriter + goldens; TYPE_PROMISE -> a P1-future rename;
     the JVM _await then-branch removal.
  6. examples/wasmcloud README + app.lisp headers still say wasmCloud cannot
     run 0.3 components (stale since Phase 6); dog-fetcher/http-client headers
     mention the deleted #+rontolisp-wasm split.

## Phase 8 handoff (superseded -- Phases 8-10 landed 2026-07-17, see Status)

State at the Phase-8 handoff (2026-07-17): Phases 0-6 committed `6dc8d12`;
Phase 7 committed `9d4c05d`. Everything below in this section described the
Phase-7 code map and the Phase-8 pointers; it is kept for the class map.

Phase 7 code map (all in `codegen/wasm`, package-private):

- `WasmAsyncEmit` -- THE state-machine emitter: compileResume (registers the
  resume LambdaInfo, builds prologue+body, returns Resume{funcId, funcIndex,
  localCount}), buildEntryBody, emitStartEntry (top-level; suspension =
  `unreachable`, REPLACE THIS with the Phase-8 event loop),
  compileGuardedProgn/compileGuardedStatement/emitRangeGuard/emitInRange,
  compileAwait (the suspend point; also where a pending future's waiter is
  registered -- Phase 8's host-backed futures additionally need the
  waitable-set join here or in the registry), compileAsyncLambdaValue,
  freshCtx (builds a Ctx sharing module-wide state; add new Ctx fields HERE and
  in Ctx.Builder or sub-compilations silently lose them).
- `WasmFutureRuntimeBuilder` -- _future_new/_settle/_reject/_add_waiter/_wake/
  _poll at ctx.asyncFuncBase + OFF_*. `_poll`'s TYPE_PROMISE branch calls
  FUNC_PROMISE_AWAIT (the BLOCKING legacy seam): Phase 8 replaces the
  wit-import async-call promise chains with pending TYPE_FUTUREs
  (_subtask_future) and deletes that branch last. TYPE_FUTURE.source (field 3)
  is reserved for the host-waitable registry key.
- `WasmAwaitAnalysis.countAwaits` -- state counting; MUST stay in lockstep with
  LispAsync.check's traversal and with emission order (assertStates enforces).
- `WasmAwaitNormalizer.hoistCallArgs` -- strict-call arg hoist, called from
  WasmExprCompiler.compileCons (asyncResume mode only).
- `WasmFutureInternalCompiler` -- rontolisp::%future-new/-settle/-reject test
  primitives (keep; Phase-8 tests can keep using them, Phase 10 decides fate).
- Touched compilers: If/While/Let/Progn/Block/Setq/Defvar/Return/HandlerCase/
  UnwindProtect (guard branches), AwaitCompiler (delegates to WasmAsyncEmit),
  PromisepCompiler (futurep), RuntimeBuilder (print "#<FUTURE>" branch, builder
  signature +futureTypeIndex), ExportCompiler (poll after async targets),
  LambdaCompiler (emitClosureValue extracted), LispNames (+3 internals),
  WasmLispCompiler (asyncMode wiring, rewriteTopLevelAsyncDefuns, AsyncResume,
  LambdaInfo +precompiled component, Ctx +futureTypeIndex/frameTypeIndex/
  asyncFuncBase/asyncDefunNames/asyncResume/asyncSpine*/asyncHoistCounter).

Phase 8 pointers (plan section "Phase 8 -- import 層 + スケジューラ本稼働"):

- Delete `WasmComponentImportCompiler.emitAsyncAwaitBody` (blocking spin) and
  the emitBlockedWait plumbing; async wit-import calls become
  `_subtask_future(token)` -> pending TYPE_FUTURE registered in a
  waitable->future GC table; RETURNED-eagerly (subtask 0) -> settled.
- Turn the serve builder's stub callback export ("async_cb", currently
  `unreachable` -- see WasmServeComponentBuilder / Phase 6 note (e)) into the
  real scheduler: EVENT_SUBTASK(RETURNED) -> lift + subtask.drop + settle +
  wake; EVENT_STREAM/FUTURE_* -> packed decode + settle; root frame done ->
  EXIT else WAIT|set<<4. Byte encodings + event constants are all in the
  Phase 0 spike section of this file.
- `run` needs the callback lift too (canonLiftMemoryUtf8AsyncCallback exists
  since Phase 5) once the top level can genuinely suspend; emitStartEntry's
  `unreachable` becomes task.return + the wait loop, per the scheduler design
  notes (one waitable-set per task, per-task doorbell stream for cross-task
  wakeup -- spike findings 5/6).
- WitImportDirective: delete thenDefun/awaitUnwrapDefun/%member-await, wire
  %subtask-future; interpreter/JVM wit-providers wrap sync results in settled
  futures.
- wait-for on component: lower to wasi:clocks monotonic-clock `wait-for`
  (import block currently only declares `now`) -- first real pending-future
  source outside http.
- Known seam to resolve or document: `rontolisp:then` on TYPE_FUTURE (legacy;
  Phase 10 deletes then/promisep + P1 TYPE_PROMISE machinery per the deletion
  list).

Verification recipe used in Phase 7 (repeat for Phase 8): full suite +
`-Dtest=WasmLispCompilerIntegrationTest` (Docker; do NOT run other maven
builds concurrently -- a parallel `-Pweb compile` clobbers target/classes and
fakes 90+ NoClassDefFound failures), manual 4-backend smoke, `-Pweb compile`,
javadoc (Version-class error is the allowed exception). Deferred-to-final:
native CiSpecE2eTest + wasmCloud wash + docs (`-W exceptions=y` note, backends
matrix, .kb/async-await.md update for the state machines).

## rontolisp:wait-for (DONE 2026-07-16, user-approved addition)

`(rontolisp:wait-for ms)` = a future settling to nil after `ms` milliseconds
(non-negative integer). Named wait-for (NOT sleep -- cl:sleep exists with
blocking seconds semantics), argument in milliseconds; the async counterpart of
cl:sleep. Mirrors `wasi:clocks/monotonic-clock@0.3.0`'s `wait-for: async
func(how-long: duration)` for a future component implementation.

- Interpreter: `AsyncRuntime.timer(millis)` = `new CompletableFuture()
  .completeOnTimeout(nil, ms, MILLISECONDS)` (the JDK's shared delayer thread;
  no new thread site). `Target_AsyncRuntime.timer` (Web Image) settles
  immediately (no timer thread in the browser worker). Registered in
  Environment with non-negative-LispInteger validation.
- JVM: hand-assembled `_wait_for` in JvmAsyncRuntimeBuilder (Long check +
  LCMP >= 0 + completeOnTimeout); dispatched via JvmAsyncOpsCompiler; counts
  toward usesAsyncRuntime. LESSON: the first cut did DUP2/LCMP/IFLT leaving a
  long on the stack into the shared `bad` label reached stack-empty from the
  instanceof branch -- inconsistent merge depth fails even the INFERENCE
  verifier, and when the v50 failover ALSO fails HotSpot reports the original
  typechecker error ("Expecting a stackmap frame") pointing at an UNRELATED
  method (_lispToDisplayString), which is maximally misleading. Fixed by
  LSTORE_1 before the compare so every path into `bad` is stack-empty.
- WASM (both P1 + component): clear compile error "rontolisp:wait-for requires
  the interpreter or the JVM backend (no host timer is wired on the WASM
  backends yet)". `--no-gc`: in the async-surface rejection list.
- Component follow-up (Phase 8+ material): lower to wasi:clocks
  monotonic-clock `wait-for` -- needs the import block regenerated (the
  current instance type only declares `now`) and either a blocking-wait park
  (Phase 6 style) or a real pending future (Phase 8 scheduler).
- Tests: AsyncEvalTest (settles-to-nil, delay-order-not-start-order,
  negative/non-integer reject), JvmAsyncCompilerTest (same trio),
  WasmLispCompilerIntegrationTest + NoGcWasmCompilerTest compile errors.
- Docs: reference/functions/rontolisp-wait-for.md en+ja + catalog + curated
  table row.
