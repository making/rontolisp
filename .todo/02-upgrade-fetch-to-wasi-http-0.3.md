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

### Phase 1 -- build the shared async-canon-lower capability (http-INDEPENDENT; the real work)

Teach the general wit-import canon-lower path to marshal `stream<u8>` /
`future<T>`, deriving the component-type index from WIT (not hardcoded).

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

### Phase 2 -- unified `http.lisp` over async wasi:http@0.3 (fetch + serve in ONE)

Replaces the separate Phase-2(fetch)/Phase-3(serve) of the old plan, per the
"collapse into one module" decision.

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
