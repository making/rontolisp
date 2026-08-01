# clack-handler-rontolisp: the rontolisp handler backend for Clack

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
