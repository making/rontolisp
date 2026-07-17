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

1. sockets/stdin async promotion (tcp-accept/recv + read-line as implicit
   suspension points in async context) -- an enhancement; blocking behavior is
   correct-if-sequential today. These ops flow through the hand-written
   ADAPTERS (`adapter.wat` / `adapter-sockets.wat` / the serve p1 bridge),
   whose $await_waitable parks are invisible to the core scheduler, so the
   promotion needs either adapter-exposed non-blocking variants surfaced as
   registry entries, or core-side canon-lowered reads (item 2's machinery).
2. ~~Pending-future stream reads~~ DONE 2026-07-17: `stream.read`'s wrapper
   returns a pending TYPE_FUTURE when the host reports BLOCKED -- registry
   entries are now `(waitable . (kind . (future . data)))` (kind 0 = subtask,
   kind 1 = stream read; the staged 8K chunk buffer rides in `data`, recycled
   through a new free-list global so linear memory is bounded by CONCURRENT
   reads, not total reads), the handle joins the task waitable-set, and
   `_sched_loop` gained the EVENT_STREAM_READ dispatch (lift chunk, EOF close
   protocol via the TYPE_WASI_STREAM that `_wasi_stream_read` attaches to the
   entry, settle). http.lisp's read thunk passes the raw result through
   (chunk / nil / pending future). Overlap pinned by
   `componentPendingBodyReadOverlapsTimer` (RONTOLISP_HTTP_E2E): a wait-for
   timer fires WHILE another body drains a slow fetch body. Known limit
   (documented in `.kb/async-await.md`): a second stream-read before the
   first settles is a host trap; write-side built-ins keep the blocking park.
   REMAINING from this item: the REAL serve callback (per-task waitable-sets
   + context slots + per-task doorbell streams -- spike findings 5/6 in the
   pre-trim revision), still unobservable until a host reuses instances;
   `bridge-nogc-print.wat` stays single-task-by-design (see the todo-138
   feedback below) unless that lands.
3. One leftover from the cleanup pass, deliberately kept: `_p1_future_await`'s
   kind-1 chain branch is dead emitted code in EVERY wasm module (producer
   deleted). Removing it (and possibly shrinking TYPE_P1_FUTURE to 2 fields)
   changes every module's bytes -- fold it into the next change that already
   moves the runtime bytes wholesale.

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
