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
