# clack-handler-rontolisp: the rontolisp handler backend for Clack

## Status: DONE 2026-08-02 (all four backends; `.kb/clack.md` has the mechanics)

Shipped exactly as shaped below, with these resolutions of the open choices:

- Shim system `clack-handler-rontolisp.lisp` registered under BOTH the
  hyphenated name and the dotted `clack.handler.rontolisp` — the latter is
  what lack's `find-package-or-load` actually derives (it hyphenates `/`, not
  `.`, on the handler path). The package is NOT seeded (it would short-circuit
  the find-package probe); the shim carries the defpackage, the interpreter's
  builtin loadSystem branch evaluates through the resolving eval, runtime
  `asdf:find-system` answers built-ins, and `LoadInliner` splices the shim
  eagerly after system "clack" for the compile paths.
- `run` BLOCKS (the hunchentoot shape) over the new stoppable seam
  `rontolisp::%http-server-start/-join/-stop/-port`
  (`HttpHandlerSupport.startServer` + bind address + opaque integer handle):
  with the default `:use-thread t`, `clack:stop`'s destroy-thread interrupt
  lands in the join and the unwind-protect stops that one server; verified
  live on the interpreter and the compiled JVM class (serve -> stop -> port
  closed). `:use-thread` defaults to t because `Features` INTERPRETER/JVM now
  declare `:thread-support` (`.kb/threads.md`).
- raw-body: PRE-DRAINED into a string input stream (`%bridge` is an
  async-defun; the one await lives there). remote-addr/-port ship as ""/nil —
  the HttpPlistShape extension stays a follow-up.
- Response bodies: list-of-strings / string / (vector (unsigned-byte 8)) /
  nil; a PATHNAME body signals a clear error like the FUNCTION (streaming)
  body — a deliberate v1 narrowing of the shape below (static files need a
  binary file read; revisit with the streaming work). Documented in the guide.
- Component leg: nested-directive detection in HttpLibrary/HttpHandlerInliner
  landed as shaped; bridge+export append AFTER the program so the qualified
  handler name resolves. Preview 1: http-handler AND the stream ops became
  CALL-time error stubs (todo-195 policy) — pinned via handler-case in the
  E2E because an uncaught P1 error is a silent trap.
- Compile-path enablers that were not in the shape: nested/computed
  asdf:load-system / ql:quickload -> call-time stubs, nested asdf:find-system
  -> args-then-nil, with-open-file non-native option values -> call-time
  stubs, FreeVarAnalyzer typecase/etypecase clause-head fix, PATHNAME joined
  CL_TYPES (all in `.kb/clack.md` / `.kb/asdf.md`).
- Tests: `ClackE2eTest` (opt-in RONTOLISP_CLACK_E2E=1; interpreter + JVM
  self-driving round trip incl. POST body + stop-proof, component under
  wasmtime serve, P1 pin) — 4/4 green; HttpHandlerTest seam group,
  HttpLibraryTest nested-detection group, LispEvaluatorAsdfTest find-system/
  load-system-shim tests, JvmLispCompilerTest etypecase-capture pin; full
  suite + native CiSpecE2eTest green (ci-spec feature count updated for
  :thread-support).
- Docs: guides/asdf-systems.md (en+ja): shim row, library row, "Running a
  Clack application" section, plus the stale bordeaux-threads (todo-227) and
  uiop:symbol-call (todo-229) rows corrected; examples/asdf/clack-hello.lisp
  + README row.

Note (user, 2026-08-02): rewriting `rontolisp:http-handler` itself into a
clack-native protocol (breaking change) was offered and deliberately not
taken: the request/response plist is the ONE WIT-derived HTTP value model
shared with `rontolisp:fetch` (HttpPlistShape), and the ~90-line shim buys
full Clack compatibility without forking that model. Re-evaluate if the shim
ever needs per-server state the single `*app*` slot cannot carry.

Difficulty: 中〜高 (the bridge itself is ~60 lines of Lisp — the spike proved
it end to end — but making `stop` real needs a stoppable server seam, and the
env/response mapping has a tail of cases: raw-body stream, byte-vector and
pathname bodies, remote-addr)

Part of the Clack milestone `.todo/223`. Depends on `.todo/224`-`.todo/226`;
`:use-thread t` additionally on `.todo/227`; the JVM and component legs on
`.todo/229`. All backends except WASM Preview 1 are in scope.

## Shape

A new built-in shim system `clack-handler-rontolisp` (`BuiltinSystems` +
`resource-config.json`) whose source defines package `clack.handler.rontolisp`
(`:use :cl`) exporting `run` / `stop`, bridging onto `rontolisp:http-handler`.
Clack finds it by name: `(clackup app :server :rontolisp)` ->
`find-handler` -> `find-package-or-load "CLACK.HANDLER.RONTOLISP"` ->
system name "clack-handler-rontolisp" -> the shim. Package found ->
`(intern "RUN" pkg)` -> apply. All interpreter-runtime, verified by the spike.

## Env mapping (request plist -> Clack env plist)

From `(:method :path :query :headers :body)` build the standard Clack env:
`:request-method` (upcased keyword), `:script-name ""`, `:path-info`,
`:query-string` (nil-or-string as-is), `:server-name`/`:server-port` (from the
run args), `:server-protocol :http/1.1`, `:url-scheme "http"`, `:request-uri`
(path + "?" + query), `:headers` (lowercased-name hash-table `:test 'equal` —
rontolisp hands an alist), `:content-type`/`:content-length` (from headers),
`:raw-body` (the request body stream; NOTE `:body` is an ASYNC stream a reader
drains with `rontolisp:await` + `read-all` — decide whether the bridge
pre-drains into a string-input-stream (simple, correct for lack-request later)
or passes through (lazy, but Clack apps expect a CL stream)), `:remote-addr` /
`:remote-port` (rontolisp's request plist has neither today — either extend
`HttpPlistShape`/`HttpHandlerSupport` with remote-addr or ship "" and document).

## Response mapping (Clack `(status headers body)` -> response plist)

`:status` = first; `:headers` = plist -> lowercased dotted alist; body:
- list of strings -> concatenated string (spike shape),
- `(vector (unsigned-byte 8))` -> string via the byte-string convention,
- pathname -> file contents (static files; needs read of binary file),
- function (streaming responder) -> v1: clear error, out of scope
  (`.todo/223`).

## run / stop

- `run (app &key port address debug &allow-other-keys)`: store the app,
  start the server. For `stop` to work the server must be STOPPABLE:
  `HttpHandlerSupport` already has the non-blocking `start(port, handler)` test
  seam + `stopAllForTesting()`; promote a per-server stop handle into a real
  (possibly internal `rontolisp:%http-*`) pair so `run` can return an acceptor
  handle and `stop` shuts that one server down. Blocking `serve` stays for the
  directive path. (`clack.handler:stop` with `:use-thread t` destroys the
  thread instead — with virtual threads the interrupt lands in accept; still
  stop the server explicitly on our side.)
- `address` is normalized by clack via usocket (`.todo/226`) before it reaches
  `run`; bind the listener to it (`HttpHandlerSupport` currently binds
  wildcard — add the bind address).

## Compile paths (in scope: JVM + WASM component; P1 = call-time error)

Both compile legs need `.todo/229` first (clackup's `(apply (intern "RUN"
handler-package) ...)` chain has no name table today) — do not route around it
with a bespoke clack special case.

- **JVM**: reuses the interpreter's `HttpHandlerSupport` server, so once 229
  lands the same shim source should compile and serve; the new stoppable-seam
  functions need their JVM lowering next to the existing serve wiring
  (`JvmHttpHandlerRuntimeBuilder`). Threads for `:use-thread t` come from
  `.todo/227`.
- **WASM component**: `HttpLibrary.process` finds the `http-handler` directive
  among TOP-LEVEL forms only, but the bridge calls it INSIDE the shim's `run`
  defun. Widen detection: a `(rontolisp:http-handler '<literal-name> ...)`
  call nested in a defun body still yields a static handler name — extract it
  for the export wiring and lower the call site to a no-op (the host owns the
  socket; instantiation runs the top-level program, clackup stores `*app*`,
  then requests arrive through the exported `handle`). `:use-thread` must be
  nil-equivalent and `stop` is meaningless under `wasmtime serve` — document,
  and make the shim degrade honestly (`#+rontolisp-wasm` branches, the shim
  Features mechanism from `.kb/asdf.md`).
- **Preview 1**: no incoming TCP by design — the socket family already lowers
  to call-time errors (todo-195 policy); pin that clackup errors there with
  the standard message, not a compile crash.

## Tests + docs (asdf-library integration checklist)

- `ClackE2eTest` (AsdfLibraryE2eSupport pattern): quickload UNPATCHED cached
  clack, clackup on an ephemeral port, HTTP round trip (method/path/headers/
  query echo), default middlewares ON (exercises builder + backtrace
  middleware + find-middleware), then `clack:stop`. THREE live legs asserting
  the same round-trip output: interpreter, compiled JVM class, WASM component
  under `wasmtime serve` (Docker image, `WasmtimeSupport`), plus the Preview 1
  call-time-error pin. Needs network OR the vendored-tree route — follow the
  ClPostgres/AsdfLibraryE2eSupport precedent for gating.
- docs: `doc/{en,ja}/guides/asdf-systems.md` row + a guide section, and an
  `examples/asdf/clack-*` demo + README row (user convention 2026-07-18).
- Memory/kb: record the handler contract in `.kb` (new `clack.md` or a section
  in `fetch-http.md`).
