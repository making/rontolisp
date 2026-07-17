# Callback-async cutover -- remaining follow-ups

The cutover itself is COMPLETE and committed (`6dc8d12` Phases 0-6, `9d4c05d`
Phase 7, `dbe4e2b` Phases 8-10 + final gates). The full plan, the Phase 0 spike
notes (byte encodings, callback protocol constants, scheduler design) and the
per-phase status log live in this file's pre-trim revision:
`git show dbe4e2b:.todo/139-callback-async-cutover.md`.

Landed 2026-07-17 (this trim's working tree, after `9376a8f`):

- Old item "wait-for on --component" DONE: `rontolisp:wait-for` lowers to
  `wasi:clocks/monotonic-clock@0.3.0`'s async `wait-for` via the `wait.lisp`
  shim (`eval/WaitForLibrary`, the http.lisp pattern) -- a PENDING TYPE_FUTURE
  through `%subtask-future`, settled by `_sched_loop`, so timers genuinely
  overlap in one instance (verified: delay order, not start order; and a
  serve handler awaiting a timer round-trips under `wasmtime serve`). The
  import blocks were regenerated with the `[async-lower]wait-for` stub import
  (which dependency-hoisted a `wasi:clocks/types@0.3.0` instance into all
  three GC blocks); every instance/type constant re-derived from wasm-tools
  dump; serve's fixed-iface path generalized to
  `WasmComponentBuilder.lowerFixedFromBlock` + `FIXED_BLOCK_IFACES` (base and
  sockets bind block-declared interfaces the same way now); `--emit-wit`
  fixtures + `WasiWitDefinitions` regenerated. Mechanics: `.kb/async-await.md`.
- Old item "mechanical cleanup pass" DONE: `TYPE_PROMISE` ->
  `TYPE_P1_FUTURE`, `WasmPromiseRuntimeBuilder` -> `WasmP1FutureRuntimeBuilder`,
  `WasmPromisepCompiler` -> `WasmFuturepCompiler`, `FUNC_PROMISE_AWAIT` ->
  `FUNC_P1_FUTURE_AWAIT` (P1 wasm bytes untouched by design -- the dead kind-1
  chain branch in the always-emitted `_p1_future_await` body is retained and
  documented so runtime bytes stay put); JVM `PromisePrint` -> `FuturePrint`;
  the JVM `_await` MARKER then-chain branch DELETED (with its thenMarker /
  obtrudeValue / awaitSelf / invoke1 refs), and `JvmFetchRuntimeBuilder`
  swept of the dead 0.2-era response-conversion constants + helpers (its
  `_await` half moved to `JvmAsyncRuntimeBuilder` long ago).

## Remaining (enhancements, in priority order)

1. ~~sockets/stdin async promotion~~ SPLIT OUT 2026-07-17 to `.todo/141`
   (sockets-stdin-canon-lower). The design question was settled with the
   user: not adapter non-blocking variants but a full canon-lower migration
   -- sockets/stdin become wit-imported Lisp-source libraries (the
   http.lisp/wait.lisp pattern) and the value-returning built-ins get an
   await-shaped async-body lowering (`WasmAwaitAnalysis` counts literal
   awaits, so no state-machine surgery). Grounding facts, phases and the
   gates recipe moved there. Items 2/3 below are to be done FIRST (user,
   2026-07-17); where item 2 would generalize `adapter-sockets.wat`, exempt
   it as single-task-by-design instead -- `.todo/141` deletes it.
2. The REAL serve callback -- the ACTIVE item, to be done TOGETHER WITH item 3
   (plan agreed with the user 2026-07-17; item 3 rides this item's wholesale
   byte move). HANDOFF PLAN for the session that picks this up:

   **Read first**: `git show dbe4e2b:.todo/139-callback-async-cutover.md` --
   the "Behavioral findings" 5/6 (context.get/set persists from initial call
   into the same task's callback; intra-component u64 streams work = the
   per-task doorbell primitive) and the whole "Scheduler design notes fixed
   by the spike" section (one waitable-set per TASK; cross-task wakeup =
   doorbell write, never resuming another task's frames in your callback).
   Also the Phase-0 spike notes there for the callback protocol byte
   encodings. Then `.kb/async-await.md` + `.kb/wasi-component.md`.

   **Current state (verified 2026-07-17)**: serve's `handle` is ALREADY a
   callback async lift, but the callback is a never-invoked STUB
   (`WasmServeComponentBuilder.java` ~256-260) because the export wrapper
   parks inside blocking `waitable-set.wait` -- `_sched_loop`
   (`WasmFutureRuntimeBuilder.buildSchedLoop`, the blocking wait is at
   ~line 668) drives everything from synchronous boundaries. Per-task
   waitable-sets + the kind-tagged registry already exist (item 2's landed
   half). What is missing is returning control to the host instead of
   blocking.

   **The flip**:
   - Factor `buildSchedLoop` into (a) the event-dispatch core (the
     EVENT_SUBTASK / EVENT_STREAM_READ arms, unchanged) and (b) the driver.
     The BLOCKING driver stays for the synchronous boundaries -- top-level
     `_start` (the `run` export is an async-typed SYNC-ABI 0x43 lift, spike
     finding 8; it blocks legally, finding 1) and wasm-export wrappers whose
     async target suspended. The serve `handle` path gets a CALLBACK driver:
     on pending, return `CALLBACK_WAIT | (set << 4)` to the host; the real
     callback function receives the event, runs the dispatch core, resumes
     the task's frames, and returns WAIT again or EXIT (task.return already
     happened mid-task -- that part is landed).
   - Task identity across re-entry = `context.get/set` (2 i32 slots per
     task, finding 5): store the task's state root (registry/frame head) at
     initial call, reload in the callback.
   - Cross-task wakeup = per-task doorbell: each task owns an
     intra-component u64 stream with a standing pending read joined into its
     set; a task settling a guest future another task awaits writes 1 to the
     owner's doorbell-writable (completes immediately, finding 6); the
     owner's callback fires, re-arms the read, resumes its own frames.
   - EXEMPT (document in each header, do NOT generalize): `adapter.wat` /
     `adapter-sockets.wat` / `adapter-http-server-p1.wat` /
     `bridge-nogc-print.wat` stay single-task-by-design -- `.todo/141`
     deletes adapter-sockets, and the others' ops are sync-boundary-only
     today. Their $await_waitable parks are legal from a callback task
     (finding 1).

   **Verification**: the protocol flip itself is observable SINGLE-task on
   current hosts (wasmtime serve re-enters the callback for every pending
   event even with one request) -- all existing serve/fetch/timer/overlap
   gates must stay green through it. The multi-task payoff (two requests
   interleaving in ONE instance) is what current hosts don't exercise
   (wasmtime serve re-instantiates per request): add a wast-based
   intra-instance probe in the cb-spike style (a driver invoking the async
   export twice in one instance) so the doorbell/context machinery is
   actually observed, not just carried. Gates recipe: full suite, `-Pweb`
   compile, native build + CiSpecE2eTest, wasmCloud `wash dev` via PATH
   shim, opt-in RONTOLISP_HTTP_E2E (incl. the overlap pin).
3. Fold INTO item 2's byte move: delete `_p1_future_await`'s dead kind-1
   chain branch (producer deleted with `rontolisp:then`; currently emitted
   into EVERY wasm module) and evaluate shrinking TYPE_P1_FUTURE to 2 fields.
   Component bytes move wholesale with item 2 anyway; P1 module bytes move
   only from this deletion, which is the accepted cost.

## Feedback from todo-138 (2026-07-17, the nogc-print 0.3 purge)

Todo-138 executed the async-built-in + blocking `waitable-set.wait` pattern in
a FOURTH hand-written site (`bridge-nogc-print.wat`, alongside `adapter.wat` /
`adapter-sockets.wat` / `adapter-http-server-p1.wat`) and re-confirmed the
spike findings 1/8 end to end (plain 0x43 async lift, zero flags, wasmtime 46).
What that run teaches the remaining items:

- **Regen + re-derive workflow** (re-proven by the wait-for item): with
  wasm-tools 1.252.0, `regen.sh` left every UNTOUCHED blob byte-identical
  (`git status` on the resources tree is the quick check). A cross-interface
  `use` in a newly imported member dependency-hoists the `types` interface as
  its own import INSTANCE (not just an alias), shifting instance indices too --
  and in the serve block it hoisted to instance 0, shifting EVERYTHING. Read
  the dump, don't infer. After any git-stash byte-identity check, the exec jar
  in `target/` is stale -- rebuild before compiling manual verification
  artifacts.
- **Item 2 (per-task waitable-sets)**: `bridge-nogc-print.wat` makes the same
  single-task assumption as the adapters -- ONE cached waitable-set (a module
  global there) and a fixed per-call scratch (the core's 16-byte iov cell).
  Fine for the reactor + `--invoke` shape, but if item 2 ever generalizes the
  park to per-task waitable-sets/context slots, the nogc-print bridge is a
  fourth copy of the pattern to visit (or to explicitly exempt as
  single-task-by-design).
