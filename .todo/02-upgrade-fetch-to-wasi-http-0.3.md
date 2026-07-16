# Redesign `rontolisp:fetch` + `rontolisp:http-handler` on async wasi:http@0.3 (zero-based)

**Status:** READY TO IMPLEMENT. The upstream blocker this file used to record is
GONE (verified 2026-07-16), and the one keystone risk was discharged by a spike
on wasmtime 46. What remains is one internal capability plus a clean-slate
rewrite of the HTTP glue.

**Backward compatibility is explicitly waived** (user, 2026-07-16): this is a
redesign toward the ideal 0.3 shape, NOT a port of the 0.2 shapes. Hard cutover,
no 0.2 fallback path. (wasmtime 46 still hosts 0.2, so nothing forces the timing
-- the cutover is our choice, not the host's.)

## TL;DR

- **Why we were on 0.2:** upstream had no async `wasi:http@0.3` and no host to
  run it. That premise LAPSED -- WASI 0.3.0 shipped 2026-06-11 and wasmtime 46
  hosts it by default. The block is now purely INTERNAL: the shared canon-lower
  path cannot marshal `stream<u8>` / `future<T>`.
- **Spike verdict (2026-07-16):** the async lift, the stream/future canonical
  built-ins, and `wasmtime serve` ALL run on wasmtime 46 today. The feared
  "host wants a different call shape -> CPS-rewrite the whole backend"
  showstopper is REFUTED. What is left is internal plumbing, not new-technology
  risk.
- **Design stance:** keep the good foundation (WIT-as-IDL, promise API, stackful
  lift, i31 handles); take the 0.3 simplifications for free (drop the triple-nest
  unwrap, unify errors on conditions, symmetric body API); actively improve (ONE
  unified `http.lisp`, type-driven lowering, `with-*` drop discipline); do not
  over-engineer (no first-class future/stream user values, no scheduler).

---

## 1. Why HTTP was on 0.2 -- and why the block is gone

### 1a. The old reason (now FALSE -- this file previously said "blocked on upstream")

The premise was: async `wasi:http@0.3` does not exist upstream and no host runs
it. Verified WRONG as of 2026-07-16 (primary sources via `gh api`):

- `WebAssembly/WASI` carries a FINAL non-RC tag `v0.3.0`, published 2026-06-11
  (`prerelease=false`). At that tag `proposals/http/wit/{worlds,types}.wit` both
  begin `package wasi:http@0.3.0;`.
- The unified world: `world service { export handler; }` +
  `world middleware { import handler; ... }`;
  `interface handler { handle: async func(request) -> result<response, error-code>; }`.
  `client.send`'s signature is IDENTICAL to `handler.handle` (same package).
- Bodies use component-model built-ins, not `wasi:io`:
  `contents: option<stream<u8>>`,
  `trailers: future<result<option<trailers>, error-code>>`,
  `consume-body -> tuple<stream<u8>, future<result<_, error-code>>>`. The http
  `deps.toml` at v0.3.0 lists only `cli` + `clocks` -- `wasi:io` is DROPPED.
- Host: wasmtime `v46.0.1` (2026-06-24) release notes: "Wasmtime now supports
  WASI 0.3.0 by default and the `component-model-async` [feature]". `wasmtime
  serve` runs a 0.3 `wasi:http/service` component.
- The standalone `WebAssembly/wasi-http` repo is archived (merged into
  `WebAssembly/WASI`).

The prior note ("last checked 2026-06-25, unchanged") simply missed a
finalization that had landed ~2 weeks before its own check date.

### 1b. The real (internal) reason -- still valid

fetch and serve are Lisp (`fetch.lisp` / `serve.lisp`) over canon-lowered
`wasi:http` USER imports, and all of `fetch.wit` / `fetch.lisp` / `serve.lisp`
are pinned `@0.2.0` (`fetch.lisp:23-27`, `serve.lisp:29-31`). The 0.2 interfaces
use FLAT resource+poll types (`input/output-stream`, `pollable`,
`future-incoming-response`) that the shared marshaller already handles. The 0.3
interfaces use the async component-model ABI (`stream<u8>` / `future<T>`), and
the shared canon-lower path CANNOT marshal those (see section 4). Until that
capability exists, there is no 0.3 ABI rontolisp can emit -- even though the ABI
and a host now exist upstream.

---

## 2. Spike verdict (2026-07-16) -- the keystone is resolved

The one design-level unknown was: does rontolisp's existing STACKFUL async lift
(functype `0x40`->`0x43`, used by `wasm-export :async t`) satisfy an async
component export on wasmtime's 0.3 runtime, or does the host expect a
stackless/callback task shape (which would mean CPS-transforming the backend --
a showstopper)? Spiked directly on wasmtime 46.0.1:

- **T1 -- async lift runs.** Compiled `(defun f (n) (print n) (* n 2))` +
  `(rontolisp:wasm-export 'f :params '(:int) :returns :int :async t)` to
  `--component`; the export is `async func(p0: s32) -> s32` (a `canon lift` on an
  async functype). Invoked:
  `wasmtime run -W gc=y -W component-model-more-async-builtins=y --invoke 'f(7)' f.wasm`
  -> prints `7` (I/O ran INSIDE the async task) and returns `14`. So wasmtime 46
  drives our stackful lift to completion. **Showstopper refuted.**
- **T1b -- stream/future built-ins already run.** `wasm-tools print` of that
  module shows `(canon stream.read 17 ...)` / `(canon future.read 19 ...)` -- the
  async canonical built-ins are already EMITTED and EXERCISED for base I/O, but
  wired only into the hand-assembled base adapter with HARDCODED type indices
  (17/19/21), not derived from WIT. This is exactly the gap in section 4.
- **T2 -- serve host path runs.** The existing 0.2 serve component runs
  end-to-end under
  `wasmtime serve -W gc=y -W exceptions=y -W component-model-more-async-builtins=y serve.wasm`;
  `curl` gets `HTTP/1.1 200 OK` + body. Also confirms wasmtime 46 STILL hosts 0.2
  (dual-host -- no forced cutover timing).

Run flags for the record: serve needs `-W gc=y -W exceptions=y -W
component-model-more-async-builtins=y` (exceptions because serve compiles in EH
mode); a plain async `--invoke` needs `-W gc=y -W
component-model-more-async-builtins=y`.

Conclusion: every 0.3 primitive rontolisp needs -- async lift, stream/future
canonical built-ins, the `wasmtime serve` HTTP path -- is proven on the target
host. The migration is now internal plumbing (route the proven primitives
through the general wit-import path), not a bet on unproven technology.

---

## 3. Design stance (zero-based; back-compat waived)

### Keep -- already the refined choice (a clean slate lands here anyway)

- **WIT-as-IDL / Lisp-over-wit-import** (the `.todo/124` north star). Glue is
  ordinary Lisp, no core codegen; http/sockets/keyvalue converge on ONE
  mechanism. This is why the WAT adapters were deleted; do not regress it.
- **The promise as the user-facing async API** (`then`/`await`, `TYPE_PROMISE`).
  It is a 4-backend contract (interpreter/JVM have their own promise). fetch
  returns a promise; do NOT expose the raw 0.3 `future` to users.
- **The stackful async lift.** Proven (T1). Its virtue: you WRITE synchronous-
  looking Lisp (blocking-read loops) and it RUNS as an async task that suspends
  at `future.read`. Keep the synchronous-looking glue style; do not rewrite it
  into an explicit callback style.
- **stream/future = i31 handle.** No new value type. A `stream<u8>`/`future<T>`
  crosses as a bare i32 handle, represented exactly like every WASI resource
  handle today (`boxI31`). What you read OUT is an ordinary value (`stream.read`
  -> byte string; `future.read` of `result<...>` -> the existing result/condition
  lift). See `.todo` discussion 2026-07-16; only `WitTypeMapper` changes (sec 4).

### Take for free from 0.3 -- do NOT port the 0.2 shapes

- **The triple-nest unwrap disappears.** 0.2 reads
  `option<result<result<incoming-response, error-code>, _>>`
  (`fetch.lisp:120-126`, the double `%wit-result`). 0.3 is
  `future<result<response, error-code>>` -> `(%future-read future)` yields one
  `result`. Delete the nested-unwrap helper; do not carry it over.
- **Errors unify on the condition system.** 0.3 has `-> result<_, error-code>`
  everywhere (send, receive, body). rontolisp already maps a result error arm to
  a condition signal, so send/receive/body/trailers all sit on ONE error model.
  Drop the 0.2 "nil-on-failure" + `pollable.block` ad-hoc handling.
- **The body API is symmetric.** 0.3 request and response are the same shape
  (`contents: option<stream<u8>>`, `consume-body -> tuple<stream<u8>, future<...>>`),
  so the send-body-write and receive-body-read code is a mirror -- share it
  between the two directions instead of the 0.2 incoming/outgoing split.

### Actively improve now -- clearly better on a clean slate

- **Collapse `fetch.lisp` + `serve.lisp` into ONE `http.lisp`.** In 0.3
  `client.send` (outgoing) and `handler.handle` (incoming) have the SAME
  signature in the SAME package, and the world unifies to `service` (export
  `handler` + import `client`). One module with a shared request->response core:
  `fetch` calls `client.send`; the exported handler implements `handler.handle`.
  This should also collapse the `WasmServeComponentBuilder` NARROW/WIDE
  `ServeBlock` split (serve-only vs serve+fetch) -- both become the same
  `service` world. Do not port the two-file / two-block structure.
- **Type-driven uniform lowering, not a bespoke `BuiltIn` kind.** Rather than
  add a special `stream/future` case, teach `WitTypeMapper` / `WitCanonicalAbi` /
  `WitComponentTypeEncoder` the stream/future TYPES so the SAME machinery that
  generates resource-method bindings also generates the `stream.read` /
  `future.read` calls. Keep the special-casing to a minimum; let the type system
  drive it.
- **`with-*` drop discipline for handle lifetime.** 0.3 puts a `stream` + a
  `trailers` future on every body, and an unfinished/undropped resource TRAPS
  (`outgoing-body.finish` traps without drop; cf. the wit-cross-interface note).
  The current glue lists drops by hand and is fragile (`fetch.lisp:92-95`,
  `serve.lisp:108-112`). `unwind-protect` works on wasm now (todo 129), so wrap
  handle lifetimes in a `with-http-stream` / `with-resource` macro that
  GUARANTEES drop on any exit. (wasm-GC has no finalizer, so drop stays explicit
  -- but scope-guaranteed, not manually sequenced.)

### Do NOT over-engineer

- **No first-class future/stream USER values** ("method B" / language-level
  async). Tempting on 0.3, but out of the http-only scope and a 4-backend
  burden. Keep stream/future internal to `http.lisp`.
- **No custom async scheduler / waitable-set.** The stackful lift already gives
  "write sync, run async"; explicit scheduling is not needed for http.
- **No GC-finalizer auto-drop.** wasm-GC has none; the `with-*` macro is the
  realistic best.

---

## 4. The internal capability gap -- the real work

The async canonical-ABI cannot flow through the general user wit-import
canon-lower path. Concretely:

- **`WitTypeMapper.java:150-151`** classifies both as unsupported (everything
  else -- list, option, result, own/borrow-handle -- is mapped):
  ```java
  case WitType.StreamOf ignored -> Rep.UNSUPPORTED;   // -> should be Rep.HANDLE
  case WitType.FutureOf ignored -> Rep.UNSUPPORTED;   // -> should be Rep.HANDLE
  ```
- **`WasmComponentImportCompiler`** marshals FLAT types only; `stream`/`future`
  hit `throw paramUnsupported(...)` (guest arms around `:705-721` /
  `:1015-1050` / `:1108-1190`). Handles are already lowered/lifted trivially
  (`emitLowerHandleParam :728`, `boxI31 :1035-1036`) -- the stream/future handle
  reuses that; the NEW part is CALLING the canonical built-ins on it.
- **The async built-in ENCODERS already exist** (`am.ik.wasm.ComponentWriter`:
  `canonStream{New,Read,Write,DropReadable,DropWritable}` /
  `canonFuture{New,Read,DropReadable}`) but their ONLY caller is the
  hand-assembled base adapter in `WasmComponentBuilder.java:739-751` with
  HARDCODED type indices (`T_STREAM`, `T_CLI_FUTURE`, `T_FS_FUTURE`), never
  derived from WIT.
- Downstream layers that also reject stream/future (per 2026-07-16 survey;
  verify line numbers before editing): `WitCanonicalAbi` size/align/flatTypes/
  variantInfo/recordInfo default-throw; `WitComponentTypeEncoder.valType`
  default-throw; `WitImportDirective.validateComponentParam/Result` throw
  `asyncOnly` for `--component`. WIT PARSING of `stream<T>`/`future<T>` is
  already DONE (`WitType.StreamOf/FutureOf`) -- no parser work.

So the capability = teach these layers the stream/future TYPE (map to a
handle-like rep, size=align=4, flatTypes=[I32], component-type index derived
from WIT), emit the matching `canon stream.*`/`future.read` lowering from the
general path (a new data-driven emission loop parallel to the hardcoded
`WasmComponentBuilder` block), and expose Lisp-callable `%stream-read` /
`%stream-write` / `%future-read` bindings. Then `http.lisp` drives 0.3 bodies.

This same capability is the shared enabler for externalizing sockets
(`sockets.lisp` over `wasi:sockets@0.3`, deleting `adapter-sockets.wat`) and,
eventually, deleting `adapter.wat` itself -- so build it as a GENERAL capability,
not an http special case.

---

## 5. Roadmap

### Phase 0 -- correct the record + spike -- **DONE (2026-07-16)**

- Upstream re-verified: 0.3.0 final, wasmtime 46 hosts it (section 1a).
- Spike passed: stackful async lift + stream/future built-ins + `wasmtime serve`
  all run on wasmtime 46 (section 2). Keystone resolved -> async substrate is
  REUSABLE (no CPS rewrite).
- This file rewritten from "blocked on upstream" to this plan.

### Phase 1 -- build the shared async-canon-lower capability -- **DONE (2026-07-16, uncommitted)**

Landed in two halves. Full suite green; the async component VALIDATES under
`wasm-tools validate --features all` (the strongest host-free check; the
runtime E2E rides Phase 2's `wasmtime serve`).

**Phase 1a (type acceptance + derivation).** The wip branch
`wip/wasi-http-0.3-phase1a` implementation was adopted after review (it matched
the zero-based design): `WitTypeMapper` reps `STREAM_HANDLE`/`FUTURE_HANDLE`;
`WitCanonicalAbi` leaf arms (size=align=4, flat=[I32], composites recurse);
`WitComponentTypeEncoder` refactored `valType` -> `definedIndexOf` and encodes
`definedStream`/`definedFuture` (payload chain walked, alias-to-primitive
peeled); `WitImportDirective` threads a `component` flag and accepts
stream<u8>/parameterized futures on `--component` only.

**Phase 1b (the codegen half) -- the design that landed:**

- **Async built-ins are derived from WIT type ALIASES** (no new directive
  syntax): a `type body-stream = stream<u8>` / `type trailers-future =
  future<...>` alias in the bound interface binds `<alias>-new` (returns the
  `(readable . writable)` cons), `<alias>-read` (stream: one blocking chunk as a
  byte string, nil = EOF; future: the lifted payload, nil = dropped),
  `<alias>-write` (stream: stage + blocking write, returns count; future: lower
  the value into memory, returns t/nil), `<alias>-drop-readable`,
  `<alias>-drop-writable`. The drop rule applies: bound ONLY when the program
  names them (byte-identity), `--component` only (clear error if referenced
  elsewhere, silently skipped on bind-everything passes). Our embedded 0.3
  http WIT will carry these aliases (they are transparent -- they change no
  instance type and no host contract).
- `WasmComponentImportCompiler`: `Async` member kind (`(:async "alias" "op"
  "lisp-name")` in the `%component-import` form; core import field =
  `[async-<op>]<alias>`); stream/future param/result arms = bare i32 handle;
  **new `emitLowerAt`** -- the memory-direction mirror of `emitLiftAt` (store a
  Lisp value at its canonical layout: prims, string/list<u8> staging, handles,
  variants/options/results by disc+payload, records/tuples by field offsets) --
  used by `future.write` today and by Phase 2's serve export result tomorrow.
- `WasmComponentBuilder.appendUserImports`: a data-driven async block after the
  drops -- component-LEVEL types derived by the new `WitComponentLevelTypes`
  (structural types declared fresh -- value types are structural -- and
  RESOURCES projected out of the owning instance via the shared `outerOf` map;
  async-reached resources are merged into `provides` so the instance type
  exports them), then one `canon stream.*`/`future.*` per bound op (a CORE func
  with no component func, the drop precedent; `future.read` gets realloc when
  its payload stages memory). **`appendUserImports` now RETURNS the consumed
  counts** (`Appended{types, componentFuncs, coreFuncs}`) and both builders use
  the returned record -- the static pre-count helpers are gone, so the
  "validates while lifting the wrong function" double-bookkeeping hazard is
  structurally eliminated.
- `WasmLispCompiler`: async wrappers are the third member walk (after decls,
  drops) in all four passes (defun+slot, importBodySlots, ImportSlot, body
  fill); they force the memory helpers on.
- `ComponentWriter.definedPrim` added (a future over a bare primitive payload).

### Phase 1.5 -- findings that reshape Phase 2 (recorded 2026-07-16)

The sync canonical built-ins are RENDEZVOUS (unbuffered): a `stream.write` /
`future.write` blocks until the peer reads, and vice versa. Consequences:

- **A guest-only loopback deadlocks by design** (write blocks with no reader;
  nothing in-task can ever read). Runtime verification of the built-ins
  therefore REQUIRES a concurrent host peer -- i.e. Phase 2's wasi:http E2E
  itself. Phase 1's runtime-level assurance is wasm-tools validation plus the
  fixed adapter's identical call shapes.
- **serve**: `handle` cannot just RETURN the response -- a stackful task's
  return completes the task, and only then would the host read the contents
  stream, which the now-dead task can never write. 0.2's `response-outparam.set
  BEFORE writing the body` is the tell: its 0.3 equivalent is **`canon
  task.return`** (deliver the result mid-task, keep running). Phase 2 needs a
  Lisp-callable `%task-return` built-in typed by the export's result, called by
  the handler between constructing the response and writing its body; the final
  core return is then ignored. (`ComponentWriter` needs a `canonTaskReturn`
  encoder; wasmtime implements it as part of the async ABI.)
- **fetch**: a SYNC-lowered `client.send` blocks the task before the body or
  the trailers future can be written -> deadlock for every request (the host
  awaits the trailers future even for GET). Phase 2 must **async-lower `send`**
  (the canon-lower `async` option: returns subtask+status) and await its
  completion with a wrapper-contained `waitable-set.new/join/wait` loop -- no
  global scheduler, just one generated await wrapper; the natural Lisp shape is
  the existing PROMISE (a new promise kind holding the subtask + retptr, so
  `rontolisp:await` drives the waitable-set wait). Between the async send and
  the await, the guest's sync body/trailers writes rendezvous with the host's
  eager reads.

### Phase 1 original work list (kept for reference; all landed)

- `WitTypeMapper.rep` (`:150-151`): `StreamOf`/`FutureOf` -> handle-like rep.
- `WitCanonicalAbi`: add `StreamOf`/`FutureOf` arms (size=4, align=4,
  flatTypes=[I32]) so `flatSig` stops throwing. **Also handle the COMPOSITES the
  0.3 http WIT actually uses** -- `option<stream<u8>>` and
  `future<result<option<...>, error-code>>` -- not just a bare stream/future;
  verify the option/result wrappers recurse into a stream/future payload.
- `WitComponentTypeEncoder.valType`: emit `definedStream`/`definedFuture(payload)`
  memoized like list/result, deriving the payload index recursively from WIT.
- `WitImportDirective.validateComponentParam/Result`: relax the `asyncOnly` gate
  for `--component` ONLY (keep interp/JVM rejecting stream/future -- fetch/serve
  are `--component`-only splices).
- `WasmComponentImportCompiler`: ~3 stream/future i32-handle guest arms reusing
  `emitLowerHandleParam`/`boxI31`; and Lisp-callable `%stream-read` /
  `%stream-write` / `%future-read` bindings (prefer type-driven generation over a
  bespoke `BuiltIn` kind -- see design stance).
- `WasmComponentBuilder.appendUserImports`: a data-driven emission loop emitting
  `canonStream*`/`canonFuture*` (reuse the existing `ComponentWriter` encoders)
  keyed by the WIT-derived type index; re-count `userImportCoreFuncs` and every
  `userCoreFuncs`/`userTypes`/`userFuncs` offset in `buildBase`/`buildSock` AND
  `WasmServeComponentBuilder` (an async built-in is a core func with no
  component-func alias -- the same fragile index arithmetic resource drops
  perturbed).
- **Golden-test FIRST, before any http change:** prove the new data-driven
  emission reproduces the existing hand-assembled `buildBase` AND `buildSock`
  blocks BYTE-FOR-BYTE (against `T_STREAM`/`T_CLI_FUTURE`/`T_FS_FUTURE` etc.).
  This isolates the index-arithmetic reshape from the ABI migration.

Not upstream-gated -- can proceed now.

### Phase 2 -- unified `http.lisp` over async wasi:http@0.3 (fetch + serve in ONE) -- **DONE (2026-07-16, uncommitted)**

All three shapes run E2E on wasmtime 46 (manual + the Docker integration tests):
fetch under `wasmtime run -S http=y` (GET + POST body, response plist identical to
interp/JVM), serve under `wasmtime serve` (method/path/query/headers/request-body/
status/response-headers/body), and the serve+fetch proxy -- ONE component shape.

What landed (beyond the pre-recorded design):

- **`eval/http.lisp` + `eval/http.wit` + `eval/HttpLibrary`** replace
  fetch.lisp/serve.lisp/fetch.wit/FetchLibrary/ServeLibrary. The splice computes a
  REACHABILITY-based member filter from the active roots (`rontolisp:fetch` /
  `%serve-handle`), so a fetch-only program binds no serve member (no task-return --
  which would be invalid outside a serve lift) and vice versa. http.wit = the vendored
  v0.3.0 types/handler/client + a clocks/types shim + the four transparent aliases
  (`body-stream`, `trailers-future`, `transmit-future`, `handle-result`).
- **Async func members** (`client.send`): `(:async-call member start await)` in
  %component-import; start wrapper = async canon lower, token `(packed . retptr)`, NO
  staging pop; await wrapper = waitable-set new/join/wait loop (skipped when status ==
  RETURNED at start: no subtask exists then) + subtask/set drop + emitLiftAt; public
  defun = `(rontolisp:then (start ...) #'awaited)` with a `%member-awaited` unwrap
  defun for result-returning members, so awaiting SIGNALS the error arm (parity with
  the interpreter/JVM fetch, which signal at await time -- the 0.2 nil-on-failure
  convention is gone by design). Fetch does NOT need
  `-W component-model-async-stackful=y` (async LOWER is in the default feature set).
- **task-return members**: a non-stream/future type alias binds
  `<alias>-task-return` (component-only, bound only when named); the wrapper lowers
  the value with emitLowerParam and calls `canon task.return (memory, utf8)`.
- **Waitable builtins**: 5 per async-calling interface, module = the interface id,
  fields `[waitable-set-new]`/`[waitable-set-wait]`/`[waitable-set-drop]`/
  `[waitable-join]`/`[subtask-drop]`, exported by the interface's synthesized core
  instance.
- **WasmServeComponentBuilder rewritten** on ONE block
  (`import-block-http-server.bin`, regenerated from the 0.3 `uni-http-server` world:
  instances 0=http/types 1=http/client 2=random 3=system-clock 4=monotonic-clock
  5=cli/types 6=stdout 7=stderr; pre-declared aliases: type 1=request 2=response
  3=http error-code 9/11=cli error-code; first free type 13). NARROW/WIDE gone; the
  serve+fetch variant (`http-server-client`) deleted everywhere (blob, WIT variant,
  fixtures, WasiWitDefinitions, WitEmitter). The preview1 bridge
  (`adapter-http-server-p1.wat`) rewritten over the 0.3 service interfaces + stream/
  future built-ins (fd_write = the base adapter's cli path).
- **The handle export**: core sig `[i32 request] -> []` (`:returns :void`
  wasm-export), `canon lift (memory, utf8, async)` against
  `async func(request: own<request>) -> result<own<response>, error-code>` -- the
  function type is built over the block's NAMED aliases (request/response/error-code):
  the component-model export rule requires every non-structural type an exported
  function references to be a NAMED type, so the anonymous structural error-code the
  internal task-return canon uses cannot appear in the export (learned the hard way:
  "instance not valid to be used as export").
- **`WitResolver` fix**: an unqualified `use types.{...}` now resolves against the
  SAME package first (the WIT rule) -- wasi:clocks and wasi:http both define an
  interface named `types`, which made the bare-name fallback ambiguous.
- **`lowerServeIoFromBlock`** now emits every appendUserImports kind against the
  block's instances: sync decls, async calls, drops, alias built-ins (via
  WitComponentLevelTypes seeded with the block's projections), task-returns, waitable
  builtins.
- The two user-level live-host wasi:http integration tests rewritten onto 0.3 (user
  wit-import of the vendored WIT + aliases; the GET one now also proves the USER-level
  async-call promise path).
- Serve run flags: `wasmtime serve -W gc=y -W exceptions=y
  -W component-model-async-stackful=y -W component-model-more-async-builtins=y`.

Phase-3 leftovers noted below still stand (vestigial FUNC_FETCH_* seam, docs/kb,
`.kb/fetch-http.md`, examples/wasmcloud needs RE-VERIFICATION against wasmCloud's
experimental P3 support (a `wasip3`-feature wash build targeting
`0.3.0-rc-2026-03-15` -- an RC, so the version strings may not link against our
final-`@0.3.0` components; https://wasmcloud.com/blog/wasi-p3-on-wasmcloud/),
examples/net headers need the new flags). Also: http.lisp accepts the per-call bump-heap growth of an async start
(staging not popped); the with-* drop macro was NOT introduced -- drops are
straight-line in http.lisp, scope-guarantee deferred.

### Phase 2 original notes (kept for reference)

Replaces the separate Phase-2(fetch)/Phase-3(serve) of the old plan, per the
"collapse into one module" decision.

**Progress (2026-07-16):** the canonical encoders Phase 1.5's findings demand
are LANDED and pinned (`ComponentWriterTest.canonTaskAndWaitableSetEncodings`,
bytes derived empirically via `wasm-tools parse`+`dump`, the house method):
`canonTaskReturnVoid`/`canonTaskReturnType[MemoryReallocUtf8]` (0x09),
`canonWaitableSetNew` (0x1f) / `canonWaitableJoin` (0x23) /
`canonWaitableSetWait` (0x20, blocking form) / `canonWaitableSetDrop` (0x22) /
`canonSubtaskDrop` (0x0d), and `canonLowerAsyncMemoryReallocUtf8` (the `async`
canonical option = tag 0x06; core sig becomes `[flat params..., results-ptr] ->
[(status << 4) | subtask]`). `am.ik.wit` already parses `async func`
(WitFunc.async), so the directive can KEY the async lowering off the WIT
itself: an `async func` member on `--component` async-lowers.

**Host-verified constants (2026-07-16, hand-WAT spike on wasmtime 46.0.1 --
`scratchpad/spike03`, hello.wat + proxy.wat, both served + curl-verified,
proxy fetched a live python http.server through async client.send):**

- **Async lift (stackful) core sig = `[flat params] -> []`** -- the result is
  delivered EXCLUSIVELY by `task.return`; there is no final core return value
  (resolves the "final return ignored" question: nothing to ignore). The lift's
  canonical options are `(memory, string-encoding=utf8, async)` -- the `async`
  canon option (0x06) on the LIFT is what makes it stackful, and it requires
  the NEW run flag **`-W component-model-async-stackful=y`** (wasmtime 46; the
  old `:async t` wasm-export lift -- async functype, sync-ABI core, no async
  canon option -- never needed it and still runs without it).
- **`task.return` canon options = `(memory, string-encoding=utf8)`** (no
  realloc; wasm-tools' choice, validated + runs). Its core params are the flat
  lowering of the declared result type: for `result<response, error-code>` that
  is 8 flats `[i32 disc, i32, i32, i64, i32, i32, i32, i32]` (own joins into
  error-code's first flat; the widened error-code payload follows).
- **Async-lowered call packed return = `(subtask << 4) | status`**, status in
  the LOW 4 bits (observed `0x21` = subtask 2, STARTED=1). Statuses: STARTING=0,
  STARTED=1, RETURNED=2. Async lower options = `(memory, realloc, utf8, async)`;
  core sig `[flat params..., i32 retptr] -> [i32 packed]`.
- **waitable-set event**: `wait(set, ptr) -> event-code`; EVENT_SUBTASK = 1,
  `mem[ptr]` = the subtask's waitable index, `mem[ptr+4]` = its state
  (RETURNED = 2). Builtin core sigs: `waitable-set.new [] -> [i32]`,
  `waitable.join [i32 waitable, i32 set] -> []`, `waitable-set.drop [i32] -> []`,
  `subtask.drop [i32] -> []`.
- **result<response, error-code> memory layout**: disc byte @0, payload @8
  (error-code's u64 payloads set align 8); ok arm's response handle = i32 @8.
- **`consume-body` MOVES its resource** (`request`/`response`): dropping the
  handle afterwards traps "unknown handle index". `request.new`/`response.new`
  move the headers fields and the trailers-future readable; `client.send` moves
  the request.
- **Rendezvous order verified**: (serve) `task.return` -> `stream.write` body ->
  `stream.drop-writable` -> `future.write` trailers ok(none); (fetch)
  async-lowered `send` -> `future.write` request-trailers ok(none) ->
  waitable-set wait -> lift result -> `consume-body` -> `stream.read`.
- **wit-component name mangling** (spike only; our own core imports keep the
  `[async-<op>]<alias>` house convention): export
  `[async-lift-stackful]<iface>#<func>`, import `[async-lower]<func>` from the
  interface module, `[task-return]<func>` from module `[export]<iface>`,
  waitable builtins from module `$root`, stream/future builtins
  `[<kind>-<op>-<N>]<cabi-func-name>` indexed within that function's signature.
- **`wasmtime run -S http=y` links `wasi:http/{types,client}@0.3.0`** -- the
  non-serve fetch path stays runnable under `wasmtime run`.

**Async-call binding design (decided): no new promise kind.** An `async func`
member binds as `pkg::%member-start` (lower args, async-lowered call, return a
token cons `(subtask . retptr)`) plus a generated `pkg::%member-await` wrapper
(waitable-set.new + waitable.join + wait loop until the subtask reports
RETURNED, then subtask.drop / waitable-set.drop and the ordinary emitLiftAt of
the result at retptr), and the public defun is plain Lisp:
`(defun send (req) (rontolisp:then (%send-start req) (function %send-await)))`
-- the EXISTING then/await promise machinery (kind 1 over a non-promise base)
does the rest. Two caveats discovered: (1) the start wrapper must NOT pop its
staging (args + retptr must outlive the call until await; accept the per-call
heap growth and note it), and (2) the waitable-set EVENT payload encoding
(event-code + two payload words) is only verifiable against a live host -- so
wire it, then verify on wasmtime serve E2E before trusting the loop.

- Vendor the v0.3.0 http WIT (`types.wit`/`worlds.wit`/`handler.wit` +
  `deps.toml` = cli+clocks) into `src/wasm-component/deps/http` as the regen
  SOURCE for the import-block `.bin`, AND write the embedded `eval/http.wit`
  (drives the wit-import splice). Keep the two copies in lockstep.
- New `eval/http.lisp` (replacing `fetch.lisp` + `serve.lisp`; update
  `FetchLibrary`/`ServeLibrary` or merge them):
  - wit-import `wasi:http/{types,handler}@0.3.x` (+ `client` for outgoing); drop
    all `wasi:io` imports.
  - `fetch` = build request resource -> `client.send` -> promise over the
    returned `future<result<response, error-code>>`; `(%future-read f)` replaces
    `pollable.block` + `future-incoming-response.subscribe/get`.
  - exported handler = implement `handler.handle: async func(request) ->
    result<response, error-code>` -- ONE param, RETURN a result (no
    `response-outparam`); `:async t` (I/O runs inside).
  - bodies via `%stream-read`/`%stream-write` (drop `input/output-stream`
    resources); SHARE the request/response body code (symmetric).
  - `with-*` drop discipline for stream/body/trailers handles.
  - re-check `%fetch-method-variant`/`%fetch-scheme-keyword`/`%serve-method-string`
    against the 0.3 `method`/`scheme` variant cases (diff vs `types.wit@v0.3.0`,
    do not assume stability).
  - trailers: an unfinished body traps -- either drive the trailers future or
    stub it immediately-ready; decide and implement (not optional).
- `ServeLibrary`/serve export side: `wasm-export` the handler as `handle` (one
  `:int` param, returned `result`, `:async t`); `WasmServeComponentBuilder` func
  type gains a result, plain lift -> async lift, export renamed
  `wasi:http/handler@0.3.0`, drop the response-outparam alias.
- `WasiWitDefinitions`: httpServer/httpServerClient worlds -> `service` /
  `middleware` (export `handler`, import `client`), drop `wasi:io`. Regenerate
  the serve import-block `.bin` from the 0.3 world (`regen.sh`) and re-derive
  every index in the (now hopefully unified) `ServeBlock`.
- Confirm the promise substrate is unaffected in SHAPE, but verify the internal
  await wiring (`WasmPromiseRuntimeBuilder` / `WasmAwaitCompiler` ->
  `FUNC_PROMISE_AWAIT`) since `future.read` replaces `pollable.block`+get.

Depends on Phase 1. Not upstream-gated for DEVELOPMENT.

### Phase 3 -- cleanup, verification, docs

- Delete the vestigial 0.2 seam: `FUNC_FETCH_START/AWAIT` trap stubs + slot/type
  constants + `FETCH_*_ADDR` cells (`WasmLispCompiler` ~`:269-271`/`:596-601`/
  `:866-883`/`:2273-2278`), `WasmFetchCompiler.compile()` dead body,
  `WasmPromiseRuntimeBuilder` kind-0 fetch-root branch. (Independent -- can start
  any time.)
- **Four-backend E2E.** interp/JVM unchanged (java.net.http). Both WASM component
  paths now run under `wasmtime serve` / component-model-async instead of `-S
  http=y`. NOTE: `CiSpecE2eTest` concatenates cases and slices STDOUT -- it CANNOT
  drive `wasmtime serve` + curl + assert. A serve round-trip needs a NEW test
  surface (spin up `wasmtime serve`, curl, assert). **This CI-green step is
  upstream-toolchain-gated** (cf. wasmtime issue #12714, a p3 `wasi:http/types`
  resource-linking failure) -- keep hard-cutover-behind-a-flag as the
  contingency if a host bug blocks landing.
- Fix stale docs (mirror en/ja per CLAUDE.md, run `DocExamplesTest#fixDetailResults`):
  - every `wasmtime serve` invocation gains `-W component-model-async-stackful=y
    -W component-model-more-async-builtins=y` and LOSES `-S http=y` (the service
    world's client import is host-provided by default):
    `doc/{en,ja}/guides/http-handler.md` (x3 each), `doc/{en,ja}/compiling/wasm.md`,
    `examples/net/*` headers, `examples/wit/keyvalue/README.md` + example headers.
  - non-serve fetch runs under `wasmtime run -S http=y -W gc=y -W exceptions=y
    -W component-model-more-async-builtins=y` (unchanged except docs must say the
    transport failure now SIGNALS `rontolisp:wit-error` at await time -- nil is only
    returned for a request that cannot be STARTED).
  - `examples/wasmcloud/**`: re-verify against wasmCloud's experimental P3 support
    (`cargo build -p wash --features wasip3`, targeting `0.3.0-rc-2026-03-15` --
    check whether the RC interface versions link against our final-`@0.3.0`
    components; https://wasmcloud.com/blog/wasi-p3-on-wasmcloud/) and update the
    README with the build flag + status (the hard cutover was deliberate; wasmtime
    46 still hosts 0.2 but rontolisp no longer emits it).
  - `doc/{en,ja}/reference/functions/rontolisp-fetch.md:76-77` -- the "wasi:http@0.3
    does not exist upstream yet" claim + the `.todo/02` pointer are now false.
  - the "headers dropped" limitation (`CLAUDE.md:106`, `.kb/fetch-http.md`) is
    REFUTED by the current `serve.lisp` (it reads+writes headers) -- correct it.
  - drop the `-S http=y` note and the "wasi:io 0.2 island" language (the
    component is uniformly 0.3 now); `doc/{en,ja}/compiling/wasm.md`.
  - update `.kb/fetch-http.md`, `.kb/wasi-component.md`, `.kb/wit.md`.
- Regenerate `--emit-wit` fixtures (`regen-wit.sh`) for the 0.3 `service`/
  `middleware` worlds; add a `WitEmitterTest` case asserting the printer emits
  `async func` + `stream<u8>`/`future<T>` canonically (not just a fixture regen).
- Confirm `%stream-read`/`%stream-write`/`%future-read` survive default-on
  `LibraryDefunPruner` + `--component` import member pruning.

### Phase 4 (optional) -- externalize sockets, then adapter.wat, over the same capability

- `sockets.lisp` over a wit-imported `wasi:sockets@0.3`, deleting
  `adapter-sockets.wat` (`WasmComponentBuilder.buildSock`'s hardcoded async
  indices become data-driven). **Precondition: confirm `wasi:sockets@0.3` is
  final/hosted** -- only `wasi:http@0.3` was verified in Phase 0.
- Evaluate moving `adapter.wat`'s preview1->0.3 base plumbing
  (`fd_write`/`fd_read`/`path_open`/`fd_close` over stream/future) into Lisp too.

Depends on Phase 1.

---

## 6. Open questions / risks

- **Composite lowering.** Does the bare stream/future i32 arm compose correctly
  through `option<stream<u8>>` and `future<result<option<...>, error-code>>`, or
  do the option/result wrappers need extra handling? (Gate Phase 1's golden test
  on the ACTUAL 0.3 composite signatures, not a bare `stream<u8>`.)
- **Trailers.** 0.3 makes trailers first-class on every body and an unfinished
  body traps. Model them or stub immediately-ready -- a correctness requirement,
  not a nicety.
- **`service` world's `client` import.** Must a serve-only component (no fetch)
  still declare/satisfy `wasi:http/client` to inhabit `service`? How does that
  interact with the hoped-for `ServeBlock` collapse?
- **`wasmtime serve` + rontolisp-emitted 0.3 handler.** T2 proved the 0.2 path;
  a rontolisp 0.3 `service`/`middleware` handler with resource-typed
  request/response is unproven until Phase 1 exists (cf. issue #12714). Go-live
  risk, not a design blocker.
- **method/scheme variants.** Diff `%*-method`/`%*-scheme` decoders against
  `types.wit@v0.3.0` -- do not assume the 0.2 cases match.
- RESOLVED by the spike (kept for the record): the stackful async lift satisfies
  wasmtime's async runtime -- no CPS rewrite needed.

---

## 7. Reference -- 0.2 (today) vs 0.3 (target)

```wit
// ---- 0.2 (serve today) ----
export wasi:http/incoming-handler@0.2.0;
handle: func(request: incoming-request, response-out: response-outparam);  // sync, 2-arg, outparam
use wasi:io/streams@0.2.0.{input-stream, output-stream};                   // depends on wasi:io

// ---- 0.3 (target) ----
world service    { export handler; }            // import client too, for fetch
world middleware { import handler; ... }
interface handler { handle: async func(request) -> result<response, error-code>; }  // async, 1-arg, returns
// client.send has the SAME signature as handler.handle (one package)
record ... { contents: option<stream<u8>>, trailers: future<result<option<trailers>, error-code>> }
// no wasi:io
```

```lisp
;; ---- 0.2 fetch read (fetch.lisp:120-131) -- triple nest + pollable ----
(%fetch-poll:pollable-block (future-incoming-response-subscribe future))
(let* ((response (%wit-result (%wit-result (cdr (future-incoming-response-get future)))))
       (istream  (incoming-body-stream ibody))
       (text     (%fetch-read-all istream ""))) ...)

;; ---- 0.3 (target, illustrative) -- one future.read, stream body ----
(let* ((response (%future-read future))                 ; future<result<response,error-code>>
       (text     (%stream-read (response-body response)))) ...)

;; ---- 0.2 serve handle (serve.lisp:142) -- 2-arg, outparam ----
(defun %serve-handle (request response-out) ...)
;; ---- 0.3 (target) -- 1-arg, returns result ----
(defun %http-handle (request) ... response)             ; async func(request) -> result<response>
```

---

## 8. Key files

- `src/main/resources/am/ik/rontolisp/eval/fetch.lisp`, `serve.lisp` -> merge into
  `http.lisp`; `fetch.wit` -> `http.wit` (@0.3).
- `src/main/java/am/ik/rontolisp/eval/{FetchLibrary,ServeLibrary}.java` (splice gates).
- `compiler/WitTypeMapper.java` (`:150-151`), `WitImportDirective.java` (asyncOnly gate).
- `codegen/wasm/WasmComponentImportCompiler.java` (flat marshaller + new stream/future arms),
  `WasmComponentBuilder.java` (`:739-751` hardcoded async wiring; `appendUserImports`),
  `WasmServeComponentBuilder.java` (ServeBlock, async lift), `WitCanonicalAbi.java`,
  `WitComponentTypeEncoder.java`, `WasiWitDefinitions.java`.
- `am.ik.wasm/ComponentWriter.java` (`canonStream*`/`canonFuture*` encoders -- already exist).
- `src/wasm-component/` (`regen.sh`, `regen-wit.sh`, deps WIT).
- `.kb/{fetch-http,wasi-component,wit}.md`, `doc/{en,ja}/...` (Phase 3 doc fixes).
