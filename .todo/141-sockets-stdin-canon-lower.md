# sockets/stdin canon-lower migration (async promotion via component-native reads)

Split out of `.todo/139` item 1 on 2026-07-17. The design question there ("(a)
await-shaped lowering over adapter non-blocking variants vs (b) promote only
canon-lowered reads") was settled with the user from a zero-base standpoint:
**neither -- migrate sockets/stdin OFF the hand-written adapters entirely**,
making sockets one more wit-imported Lisp-source library (the http.lisp /
wait.lisp / keyvalue pattern). Rationale: (a) grafts a third park mechanism
onto the adapter lineage that todo-135/138 have been deleting; (b) is a no-op
(the only canon-lowered reads -- http bodies -- are already promoted); and the
wasi:sockets@0.3 API shape maps directly onto the machinery item 2 of
`.todo/139` already built.

## Grounding facts (verified 2026-07-17)

- There is NO `tcp-recv` built-in: socket reads go through `read-line`/`read`/
  etc. -> `fd_read` (socket handles live in the file-stream handle space,
  `.kb/tcp-sockets.md`). The promotion targets are `tcp-accept` (a dedicated
  core import, `FUNC_TCP_ACCEPT`) and the `fd_read`-based reads on
  socket/stdin handles.
- On `--component` both park inside `adapter-sockets.wat` / `adapter.wat`'s
  `$await_waitable` (ONE cached waitable-set per adapter, fixed scratch) --
  invisible to the core `_sched_loop` by construction.
- `WasmAwaitAnalysis` counts literal `rontolisp:await` forms only, mirroring
  `LispAsync.check`. So an "implicit suspension point" needs NO state-machine
  surgery: a frontend rewrite that, inside async bodies only (component path
  only), turns the value-returning built-in call into
  `(rontolisp:await (%...-future ...))` is counted like any user await.
  interpreter/JVM stay untouched (virtual threads already keep other tasks
  running -- the cross-backend contract is "a blocking read in an async body
  does not stall other tasks", which they already satisfy); P1 stays
  degenerate-synchronous.
- wasi:sockets@0.3 (`src/wasm-component/deps/sockets/types.wit`) shapes:
  - `create: static func(...) -> result<tcp-socket, error-code>`
  - `bind: func(...) -> result<_, error-code>`
  - `connect: async func(...) -> result<_, error-code>` -> kind-0
    `%subtask-future`, existing machinery
  - `listen: func() -> result<stream<tcp-socket>, error-code>` -> accept =
    stream.read of a `stream<tcp-socket>` (4-byte resource-handle elements)
  - `send: func(stream<u8>) -> future<result<_, error-code>>`
  - `receive: func() -> tuple<stream<u8>, future<result<_, error-code>>>` ->
    reads ride the EXISTING kind-1 registry (`EVENT_STREAM_READ`, staged
    buffer free list, `TYPE_WASI_STREAM` EOF/close protocol)
  - `get-local-address` / `get-remote-address` -> the WASM address accessors
    (nil today, splice constraint) could become REAL here -- optional.

## Design shape

- **sockets.lisp**: a Lisp-source library over a wit-imported
  `wasi:sockets/types@0.3.0`, spliced by an `eval/SocketsLibrary` following
  the `HttpLibrary`/`WaitForLibrary` model. interpreter/JVM keep their native
  implementations (like fetch); TLS stays interpreter/JVM-only.
- **Handle-space integration**: a component socket handle becomes a
  `TYPE_WASI_STREAM`-backed entry in the file-stream handle space so the
  stream ops keep working on it. The emitted runtime reader (`read-line`/
  `read` buffering) needs a component-only seam to consume from a
  `TYPE_WASI_STREAM` instead of `fd_read`.
- **New lift**: `stream<tcp-socket>` reads (accept) -- generalize the kind-1
  lift's element kind (u8 chunks today; the old adapter had a dedicated
  `accept-read` built-in for exactly this reason).
- **Async promotion semantics**: async bodies (component only) get the
  await-shaped lowering above; sync context keeps blocking via the
  `_sched_loop` drive at the synchronous boundaries (correct-if-sequential
  preserved).
- **Phase 1 -- sockets**: sockets.lisp + accept lift + handle integration +
  lowering; DELETE `adapter-sockets.wat`; the sockets blob variant
  (`core-sockets.wat` / `uni-sockets.wit`) likely collapses into base +
  per-program appended imports -- large `--emit-wit` fixture /
  `WasiWitDefinitions` / regen churn. Read the wasm-tools dump, don't infer
  (the wait-for instance-hoisting lesson).
- **Phase 2 -- stdin**: stdin as a `TYPE_WASI_STREAM` via the block-imported
  `wasi:cli/stdin@0.3.0`; `read-line` on stdin promotes the same way (BASE
  variant affected; spike finding 3 in `git show
  dbe4e2b:.todo/139-callback-async-cutover.md` confirmed the stdin read
  starts BLOCKED even for piped input, so this is a real suspension point).
- **Non-goals (explicit)**: file reads (`path_open`/`fd_read` on files),
  the write side (`fd_write`/`file-append`, `send` may stay blocking),
  env/clock/random -- all stay on `adapter.wat` until their own todo.

## Implementation log (2026-07-17, in progress)

Design settled after a full survey (adapter-sockets.wat, WitImportDirective,
HttpLibrary/WaitForLibrary, WasmComponentImportCompiler, WasmFutureRuntimeBuilder,
runtime readers). Decisions:

1. **sockets.lisp + eval/SocketsLibrary** (HttpLibrary model, component-only
   splice, splitQualified matching). sockets.wit = the vendored
   `deps/sockets/types.wit` verbatim (host structural-subtype check) plus
   rontolisp-only TRANSPARENT aliases at the end of `types` (the http.wit
   pattern): `type sock-stream = stream<u8>` (read/write/new/drops),
   `type sock-future = future<result<_, error-code>>` (drop-readable),
   `type accept-stream = stream<tcp-socket>` (read/drop-readable; NEW element
   kind). Signatures stay verbatim -- aliases are structural, bindings come off
   the aliases, exactly like body-stream/trailers-future.
2. **tcp-* dispatch**: the `wait-for` precedent (WasmExprCompiler:246-255) --
   under `--component` the tcp names FALL THROUGH to the ordinary call path
   (resolving the spliced sockets.lisp defuns `rontolisp:tcp-connect` &c);
   Preview 1 keeps the compile error. WasmTcpCompiler's component half dies.
3. **Sync surface = `rontolisp::%future-force`** (new internal built-in,
   component-only, WASI_STREAM_NEW_INTERNAL pattern): value passthrough for
   non-futures/settled, else `call _sched_loop` (OFF_SCHED_LOOP=7,
   `_sched_loop(future)->value` already exists). sockets.lisp sync defuns =
   `(%future-force (%...-future ...))` over async-defun internals.
4. **Async promotion**: frontend rewrite (component-only, after
   rewriteAsyncSugar, inside async bodies only, mirroring LispAsync context
   rules): `(read-line s)` -> `(rontolisp:await (rontolisp::%read-line-future s))`
   etc., plus tcp-accept. The `%...-future` names are sockets.lisp async-defuns
   that dispatch: socket handle -> buffered chunk loop over awaits; anything
   else -> settled native result (await of settled passes through, so the
   rewrite is handle-type-agnostic).
5. **Handle space**: socket handle stays an int fd >= 200; the table moves to
   LISP (`rontolisp::*sock-table*` in sockets.lisp: fd -> entry with socket
   resource, kind, raw recv/send stream handles, chunk buffer + cursor + eof).
   Component read/write/close built-ins are rewritten at compile time
   (component+sockets only) into `%io-read-line`/`%io-write-line`/`%io-close`
   &c dispatch defuns in sockets.lisp (socket branch vs `%...-raw` internal
   alias names that compile to the NATIVE built-ins -- avoids rewrite
   recursion). Reads buffer a chunk per socket in the Lisp entry (documented
   divergence from "byte-at-a-time"; interpreter/JVM untouched). Writes go
   through the `sock-stream-write` alias built-in (blocking park, existing
   write-side shape). print-family -> socket: check the one fd_write funnel;
   if needed add an fd>=200 hand-assembled branch calling the SAME deduped
   canon write import via placeholder index.
6. **Accept lift**: generalize validateAsyncAlias + emitStreamRead +
   EVENT_STREAM_READ dispatch to an element KIND (u8 chunk -> string vs
   4-byte handle -> i31 int, len 1). `tcp-local-port`/`tcp-local-address`
   become REAL via `get-local-address` (record/variant results already lift).
7. **Blob collapse**: variant sockets dies -- delete adapter-sockets.wat/.wasm,
   core-sockets.wat, uni-sockets.wit, import-block-sockets.bin, buildSock,
   WasiWitDefinitions.sockets(), VARIANT_SOCKETS, regen entries, sockets.wit
   fixture; FUNC_TCP_* fixed indices 8-11 + "sock" imports + trap stubs die,
   FUNC_START 12 -> 8 (mechanical byte churn in pins). fetch+tcp and serve+tcp
   guards lift naturally (both become user imports; update the pinned error
   tests, verify combined program E2E -- resolves .todo/049 as a side effect).
8. **asyncMode/EH**: sockets.lisp carries async-defuns + a handler-case
   (nil-on-failure contract like fetch), so a component tcp program forces
   asyncMode + EH -> needs `-W exceptions=y` now (doc change; matches the
   async-component rule).

Step order: (1) sockets.wit/sockets.lisp/SocketsLibrary + CLI/playground wiring
+ %future-force + tcp dispatch change; (2) accept element-kind lift; (3) io
rewrite defuns; (4) async promotion pass; (5) blob/builder deletion + fixture
regen; (6) stdin (phase 2); gates last.

### Progress (main sources compile after each checkpoint)

- DONE: `eval/sockets.wit` (vendored types.wit + sock-stream/sock-future/
  accept-stream aliases), `eval/sockets.lisp` (table + parse-ipv4 + plumb +
  tcp-* surface + %io-*/%...-future dispatchers; url.lisp idioms only -- no
  position :start, no char=, princ-to-string for char->string),
  `eval/SocketsLibrary` (WaitForLibrary model; triggers on tcp-* OR any
  usocket: symbol since the usocket splice runs later).
- DONE: LispNames `%future-force` + 6 `%*-raw` internals;
  WasmFutureInternalCompiler FUTURE_FORCE case (CALL _sched_loop, OFF 7);
  WasmExprCompiler: tcp names error only on !component (fall through to the
  spliced defuns on component), %*-raw cases -> native IO compilers.
- DONE: WasmLispCompiler -- usesTcp/emitSockImport/fetch+tcp/serve+tcp guards
  deleted; tcp trap stubs 8-11 now UNCONDITIONAL ("retired"; FUNC_START shift
  deferred, keeps all non-socket bytes identical); non-serve component always
  VARIANT_BASE + WasmComponentBuilder.build(coreModule, decls, imports)
  (usesSockets overloads deleted).
- DONE: WasmComponentBuilder -- buildSock + S_INST_*/S_T_* constants +
  IMPORT_BLOCK_SOCKETS/ADAPTER_MODULE_SOCKETS deleted;
  rejectDuplicateUserImports added (user wit-import of sockets/types beside
  tcp-* built-ins = clear error).
- DONE: WasmSocketsRewrite (component-only compile() hook right after
  rewriteAsyncSugar, gated on a spliced `rontolisp::%io-read-line` defun;
  mirrors LispAsync context rules; sync ctx -> %io-*, async ctx reads +
  tcp-connect/tcp-accept -> (await (%...-f ...))); sockets.lisp nil-on-failure
  moved INTO %tcp-connect-f/%tcp-accept-f so both surfaces agree; CLI wiring
  after WaitForLibrary (playground needs none -- it has no --component path);
  native-image resource-config entries; sockets variant fully deleted
  (WitEmitter/WasiWitDefinitions/generator/tests/fixture/regen scripts/blobs/
  WasmTcpCompiler all removed via git rm).
- DONE: accept lift generalization -- validateAsyncAlias(+op) admits
  resource-handle streams (read/drop-readable only), validateAsyncElement
  admits them in result position (listen's result<stream<tcp-socket>,...>),
  Async.handleElement(), emitStreamRead(handleElem) (len 1, i32.load lift,
  registry kind 2), _sched_dispatch kind-2 settle branch.
- DONE: **async-lower param spill** (the real-world blocker the probe found):
  wasmtime spills async-lowered params past 4 flats (MAX_FLAT_PARAMS_ASYNC),
  connect's self+ip-socket-address = 14 flats -> FlatSig gained
  spilledParams/paramsAreaSize + WitCanonicalAbi.spillLayout (tuple layout);
  emitAsyncStartBody stores each arg via emitLowerAt into one allocated area
  and passes (argptr[, retptr]).
- VERIFIED manually on wasmtime 46 (run flags `-W gc=y -W exceptions=y
  -S tcp=y -S inherit-network=y`): listener+local-port, FULL loopback echo
  byte-identical to the interpreter (top-level = promoted-await path), the
  sync (%future-force) path in a plain defun, usocket loopback via
  ql:quickload, and **fetch+tcp in ONE component** (the old compile error is
  gone -- resolves the .todo/049 restriction). tcp-peer-address/-local-address
  now return REAL addresses on the component (were nil stubs) -- update the
  pins that expect nil.
- VERIFIED the PROMOTION GOAL itself: an async body's pending tcp-accept no
  longer stalls the instance -- a concurrent wait-for(150ms) timer fires
  FIRST, then a connect releases the accept ("timer fired" -> "after timer,
  connecting" -> "accepted"). The old adapter's $await_waitable could not do
  this; the kind-2 scheduler registration can.
- DONE: full `./mvnw test` GREEN (3775/0, Docker integration incl. the real
  loopback echo + usocket echo with REAL peer address). Test fallout fixed:
  compileComponent/compileFetchComponent/serveProgram/usocket helpers mirror
  SocketsLibrary.process (+ WitLibrary AFTER it -- the binding wrappers
  reference %wit-result), guard tests flipped to
  fetchAndTcpInOneComponentProgramCompiles /
  httpHandlerWithTcpCompilesInServeMode, arity pins updated to the defun
  messages (WasmSocketsRewrite leaves wrong-arity calls UNREWRITTEN so they
  error under the public name), WitOracle sockets test = re-parse/structural
  (user-import emitter, the fetch rationale), WitExportInliner sockets
  byte-identity keeps both halves spliced. `write-string` added to the socket
  surface (%io-write-string + %write-string-raw). `-Pweb` compile OK.
- DONE: kb/README/CLAUDE.md updates (.kb/tcp-sockets.md rewritten around
  sockets.lisp -- retired 8-11 stubs, component mechanics incl. the async
  4-flat param spill, real address accessors; src/wasm-component/README.md
  sockets section; project CLAUDE.md tcp bullet). doc/en+ja + examples
  headers delegated to a doc agent (run flags gain -W exceptions=y, nil-stub
  claims removed, combine-restriction removed).
- GATES RUN (2026-07-17): full suite 3775/0; `-Pweb` compile OK; native image
  build + CiSpecE2eTest 832/0 (all 4 backends); RONTOLISP_HTTP_E2E=1
  integration 706/0 (overlap pin included); javadoc = the known Version-class
  error only; wasmCloud `wash dev` gate PASSED with the PATH shim
  (PATH=target:... so wash rebuilds with the NEW native binary -- first
  attempt used the stale /usr/local/bin/rontolisp, watch for that) on
  examples/wasmcloud/http-handler, all three routes answer; DocExamplesTest
  green after the doc sweep (doc/en+ja run flags now carry -W exceptions=y,
  nil-accessor claims removed, combine restriction removed, write-string
  added to the socket surface docs); manual 4-backend echo: interpreter =
  JVM = component byte-identical output, P1 = clear compile error.
- Phase 1 (sockets) is FUNCTIONALLY COMPLETE on this working tree.
- REMAINING:
  1. **Phase 2 -- stdin**: stdin off adapter.wat's cached-stream `fd_read`
     branch onto the same machinery (a stdin.lisp over the block-imported
     `wasi:cli/stdin@0.3.0` read-via-stream, base variant; `read-line` on
     stdin promotes in async bodies via the SAME rewrite). Design notes from
     the phase-1 work:
     - `wasi:cli/stdin` is in the FIXED import block, so the directive must
       bind FROM the block (`FIXED_BLOCK_IFACES` needs the entry, member
       `read-via-stream`). `validateFixedMembers` currently REJECTS async
       alias built-ins on block-bound interfaces -- relax it: an alias
       built-in is typed by a COMPONENT-LEVEL stream/future type
       (`definedStream(u8)`), it aliases nothing out of the block instance,
       so it is safe there (the serve builder's `lowerServeIoFromBlock`
       already emits async kinds against block instances).
     - The `%io-*`/`%*-future` dispatch defuns are defined by sockets.lisp
       TODAY; a stdin-only program needs them too, and a program using BOTH
       must not define them twice. Split them into their own spliced library
       (io-dispatch.lisp with a stdin branch + a socket branch that
       gracefully no-ops when the sock table is absent), or make stdin.lisp
       define them only when sockets.lisp is not spliced -- decide there.
     - The stdin entry is one cached raw stream handle + chunk buffer in
       defvars (the adapter's 0x50080 cache in Lisp); the read future from
       read-via-stream is dropped immediately (EOF = stream status), the
       old adapter's pattern.
     - WasmSocketsRewrite's gate (spliced %io-read-line) already covers any
       future splicer of that defun; only the SocketsLibrary-style trigger
       (program reads stdin at all? read-line/read-char/read-byte referenced
       + component + asyncMode?) needs deciding -- a NON-async stdin program
       gains nothing from the migration, but moving it anyway kills the
       adapter's stdin branch; weigh byte-stability of non-async programs.
     Non-goals unchanged (file reads, write side, env/clock/random stay on
     adapter.wat).
  2. **`adapter.wat`'s remaining surface -- evaluation only, inherited from the
     closed `.todo/002` (its optional Phase 4, second bullet).** Once stdin is
     off the adapter, what is left there is file reads, the write side, and
     env/clock/random. Whether those move to Lisp over `wasi:cli`/`wasi:filesystem`
     /`wasi:clocks`/`wasi:random` -- and thus whether `adapter.wat` can be deleted
     outright -- is an OPEN evaluation, deliberately a non-goal of the phases
     above. `.kb/wit.md` records why the base adapter was long considered
     un-externalizable, and the sockets migration is the precedent that a wall
     there meant a missing language feature, not a fixed constraint.
  3. **FUNC_START 12 -> 8 stub collapse** (mechanical byte shift; the four
     retired tcp trap stubs at indices 8-11 are kept for byte stability
     today -- see the WasmLispCompiler comment).
  4. Pre-existing doc inconsistency the doc sweep flagged (NOT this todo's):
     usocket-socket-connect.md's "no condition handling" prose contradicts
     the todo-116 error-handling foundation; fix separately.

## Interactions / ordering

- The usocket shim rides `tcp-*`, so it follows automatically; re-run its
  suite + the address-accessor expectations.
- The `.todo/139` items 2+3 (per-task waitable-sets, real serve callback, the
  P1 kind-1 deletion) landed FIRST, 2026-07-17 (139 deleted on completion; the
  mechanics live in `.kb/async-await.md`, the full history in
  `git show dbe4e2b:.todo/139-callback-async-cutover.md`). The adapters were
  exempted as single-task-by-design (headers say so) rather than generalized;
  this todo deletes `adapter-sockets.wat`. NOTE for the stdin/sockets
  promotion: the pending reads this todo creates register with the scheduler
  registry and join the CURRENT task's waitable-set, so they work under BOTH
  drivers (the blocking `_sched_loop` and the serve callback) for free.
- Gates (the 139 item-1 recipe): full suite, `-Pweb` compile, native build +
  CiSpecE2eTest, wasmCloud `wash dev` via PATH shim, opt-in
  RONTOLISP_HTTP_E2E (incl. the overlap pin), plus manual 4-backend runs of a
  socket program and the docs examples.
