# Clack (`(ql:quickload "clack")` + `clack:clackup` on the rontolisp backend)

The `.todo/223` milestone: Eitaro Fukamachi's Clack loads VERBATIM from the
Quicklisp dist (clack-20250622-git + lack-20260101-git) and a Clack application
runs through `clack:clackup :server :rontolisp` on the interpreter, the JVM and
the WASM `--component` backend. Preview 1 has no incoming TCP by design
(`.kb/tcp-sockets.md`): the program COMPILES and `clackup` signals the standard
`http-handler` message at CALL time (see the policy note below). Pinned by
`ClackE2eTest` (opt-in `RONTOLISP_CLACK_E2E=1`: Docker for the pinned wasmtime,
network for the first quickload).

## The handler backend is a built-in shim system, found LATE-BOUND by name

`clack-handler-rontolisp.lisp` (`ShimLibraries`/`BuiltinSystems` +
`resource-config.json`) defines package `clack.handler.rontolisp` exporting
`run`/`stop`. Clack's discovery protocol drives every design choice here:
`find-handler` -> lack's `find-package-or-load` -> `(find-package
"CLACK.HANDLER.RONTOLISP")`, and only on a MISS `(asdf:find-system name nil)` +
`(asdf:load-system name :verbose nil)`, then `(apply (intern "RUN" pkg) ...)`.
Consequences, each load-bearing:

- **The package is NOT seeded in `PackageRegistry`** (unlike every other shim
  package): a pre-seeded package would satisfy the `find-package` probe before
  the system ever loads and leave `run` undefined at the apply. The shim
  carries its own `defpackage` (the leaf-module pattern) instead, and the
  interpreter's builtin-system branch of `loadSystem` evaluates shim forms
  through the RESOLVING `eval(form)` (not `eval(form, globalEnv)`) so that
  defpackage registers.
- **The system answers to TWO names**: `clack-handler-rontolisp` (the
  ecosystem-conventional spelling a user can name directly) and
  `clack.handler.rontolisp` — `find-package-or-load` derives the system name
  from the PACKAGE name by hyphenating `/` but leaving `.` alone (no
  backward-compatible flag on the handler path), so the dotted spelling is the
  one clack actually asks ASDF for. Both keys map to the one resource in
  `ShimLibraries.RESOURCES` / `BuiltinSystems`.
- **The interpreter's runtime `asdf:find-system` answers built-in systems**
  (returns the name string) even before they are loaded — that hit is what
  routes `find-package-or-load` onto its `load-system` branch at clackup time.
- **The compile paths splice the shim EAGERLY with clack**
  (`LoadInliner.spliceSystem`: after splicing system `"clack"`, it splices
  `clack-handler-rontolisp`), because a compiled program cannot load anything
  at run time: there, the `find-package` probe reads the baked package table
  (the shim's defpackage is in the program) and the interned
  `CLACK.HANDLER.RONTOLISP:RUN` resolves through the `_lookup` registry
  (todo-229, `.kb/symbol-runtime-api.md`) — the asdf branch is never reached.

## run / stop and the stoppable server seam

`run` follows the clack-handler-hunchentoot shape: it BLOCKS until the server
stops, and cleans up in an unwind. On the interpreter/JVM it rides the internal
`rontolisp::%http-server-*` seam (`HttpHandlerSupport.startServer/joinServer/
stopServer/serverPort`; the handle is an opaque integer index, the socket/mutex
convention; owned by the rontolisp package as INTERNAL symbols):

```lisp
(let ((server (rontolisp::%http-server-start app port address :raw-body :buffered)))
  (unwind-protect (progn (rontolisp::%http-server-join server) server)
    (rontolisp::%http-server-stop server)))
```

- `%http-server-start` takes the handler as a FUNCTION VALUE (unlike the
  `http-handler` directive's quoted name) plus port (0 = ephemeral,
  `%http-server-port` reads it back) and bind address (the directive binds the
  wildcard; the seam binds `address`).
- With clackup's default `:use-thread t`, `run` runs on a `bt2:make-thread`
  thread and `clack:stop` DESTROYS that thread (it never calls our `stop`):
  `destroy-thread` is `Thread.interrupt`, the interrupt lands in
  `joinServer`'s latch await, join returns NORMALLY, and the unwind-protect
  stops that one server. `stopServer` is idempotent so the unwind cleanup and
  an explicit `clack:stop` (the `:use-thread nil` protocol, unreachable in
  practice because `run` blocks) cannot double-fault.
- With `:use-thread nil`, `run` (and so `clackup`) blocks forever serving —
  hunchentoot parity, the script shape.
- JVM: `JvmHttpServerSeamCompiler` stores the handler value in the same
  `_httpHandlerFn` static slot the directive uses and reuses the whole
  injected-`handle(Request)` runtime (`JvmLispCompiler`'s `usesHttpHandler`
  gate also fires on `%http-server-start`). CONSEQUENCE, and the reason the
  shim documents ONE Clack server per process: every server dispatches through
  the one handler slot / the one `*app*` global.
- `clackup`'s default `:use-thread t` exists at all because `Features`
  INTERPRETER/JVM now include **`:thread-support`** (`.kb/threads.md`) — the
  feature upstream bordeaux-threads pushes at load time, which can never reach
  a read-time conditional here, declared statically like `:unicode`. WASM
  stays without it, so the default is nil there.

## There is no env / response bridge any more (the todo-258 cutover)

Since the Clack cutover rontolisp's own server protocol IS Clack's
(`.kb/http-server.md`): the handler receives the Clack environment and returns
the Clack response, built and normalized once in `http-server.lisp` for every
backend. The shim therefore hands the application to `%http-server-start`
DIRECTLY (no `%bridge`, no `%env`, no `%headers-table`, no `%body-string`) and
asks for the one thing Clack needs that rontolisp's default is not:
`:raw-body :buffered` — a synchronous bivalent body stream instead of the
native asynchronous one, which is what lets lack-request / circular-streams /
http-body actually read a served body (sessions, CSRF, ningle). What used to
be documented here as shim limits is now the shared model's contract:
`:remote-addr`/`:remote-port` carry the real peer on the interpreter/JVM (nil
on the component — wasi:http exposes none); duplicate request headers join
with `", "`; a bare-string response body is REFUSED; of the function-response
protocol the DELAYED form is supported and the streaming writer refused.
Still out of scope per `.todo/223`: WebSocket (`clack.socket`) and
`:swank-port`.

## WASM component / Preview 1

The shim's `#+rontolisp-wasm` `run` stores the app and calls the
`rontolisp:http-handler` DIRECTIVE with the literal quoted `'%app` (a one-line
`(funcall *app* env)` indirection — the directive requires a literal quoted
name) plus `:raw-body :buffered` — inside a defun body. `HttpLibrary.process` (and `HttpHandlerInliner.usesHttpHandler`)
therefore detect the directive NESTED in a form (quoted data excluded),
extract the static handler name for the `%serve-handle` export wiring, and
lower the call site to nil: instantiation runs the top-level program (clackup
stores `*app*`, `run` returns at once), then requests arrive through the
exported `handle`. The generated `%serve-dispatch` bridge + `wasm-export` are
appended AFTER the program so the package-qualified handler name resolves
against the shim's own (spliced) defpackage. Run flags: `wasmtime serve -W
gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y` — the socket
flags because the spliced usocket shim (a clack dependency) wit-imports
wasi:sockets. `:use-thread` is effectively nil (no `:thread-support` on WASM)
and `clack:stop` is meaningless under `wasmtime serve`.

**Two Preview-1 policy changes shipped here (the todo-195 call-time-error
policy, extended):** `rontolisp:http-handler` on Preview 1 is now a CALL-time
error stub (was a compile error) with the same "requires --component" message,
and so are `stream-read`/`stream-close`/`streamp` when no stream type exists
(Preview 1, or a non-async component) — both sit in the shim's wasm `run`/
`%app`, which are DEAD code on Preview 1 but are compiled whenever clack
loads. An uncaught error is a silent trap on Preview 1, so the E2E pins the
message through `handler-case`.

## The host-driven reactor: `clack-handler-cloudflare-workers` (`clackup`, with the export synthesized)

A SECOND built-in handler backend, `clack-handler-cloudflare-workers.lisp` (package
`clack.handler.cloudflare-workers`, both system spellings in `ShimLibraries.RESOURCES` /
`BuiltinSystems` / `resource-config.json`, again NOT seeded in `PackageRegistry`).
It exists for the hosts that call an EXPORTED FUNCTION instead of handing the
program a socket — a Cloudflare Worker, and any other `wasm-export` /
`--no-wasi` reactor embedding (the browser, node, a JVM host).

It is driven by **`clackup`**, like every other handler backend:

```lisp
(ql:quickload "clack-handler-cloudflare-workers")
(load "app.lisp")
(clack:clackup #'app
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)
```

Below `clackup` sit two functions, and BOTH are public: `handle`
`(app request-json) -> response-json` is the adapter, and `dispatch`
`(request-json) -> response-json` runs it over the application `run` stored. A
program that wants no clack at all can still call `handle` directly with its own
`wasm-export` — that is the shape this shim shipped with, and it still works.

- `handle` is `(app request-json) -> response-json`. It converts nothing itself:
  it builds the raw tuple and calls `rontolisp::%http-make-env` /
  `%http-normalize-response`, exactly as every other transport does, so it
  cannot drift from what a SERVED request sees. All that is left in the shim is
  the JSON envelope, documented in the file's own header.
- **The envelope is an API now** — `{method, target, headers, body, scheme,
  remote-addr}` in, `{status, headers, body}` out. Two parts of it are
  load-bearing and were both found by measurement: `target` is the RAW request
  target (path and query still joined, still encoded — `%http-make-env` owns
  that split, and a pre-split path leaves `:query-string` nil), and the response
  `headers` cross as an ARRAY of `[name, value]` pairs, not an object, so a
  repeated `Set-Cookie` survives.
- **`handle` CATCHES and answers 500.** On a reactor an uncaught Lisp error is a
  trap that takes the whole instance down and the host must throw the instance
  away; catching is what every other rontolisp transport already does with a
  handler error. The consequence to know: loading this shim puts `handler-case`
  in the module, so the program compiles in EH mode.
- **`run` stores the app; the EXPORT is synthesized by the compiler.** A reactor
  owns no socket, so `run` binds nothing: it `setq`s `*app*` and returns nil
  (`stop` is nil too). What replaces the socket is an export, and
  `rontolisp:wasm-export` needs a LITERAL name at compile time — which a program
  whose whole body is a `clackup` call cannot supply. So `run` carries a marker,
  `(rontolisp::%http-reactor 'dispatch "handle-request")`, that
  `eval/HttpReactorInliner` lowers to nil on the WASM backends and answers by
  APPENDING `(defun %reactor-dispatch (json) (…:dispatch json))` plus its
  `wasm-export` after the program (appended, so the package-qualified name
  resolves against the shim's own spliced `defpackage`). The precedent is exact:
  `HttpLibrary` reads the `http-handler` directive nested in the
  `clack-handler-rontolisp` shim's `run` the same way. It is a SEPARATE marker,
  not an overload of `http-handler`, because that directive means "bind a
  socket" everywhere else — which is exactly what made the `:rontolisp` backend
  trap here (`.todo/281`'s `_initialize TRAPPED` was that directive, not
  `clackup`).
- **The marker is `#+rontolisp-wasm` and nothing defines it.** On the
  interpreter and the JVM the shim never reads the form, because there is no
  export to synthesize: the host calls `dispatch` as an ordinary function. That
  is why `dispatch` is EXPORTED from the package rather than being a
  compiler-only internal, and why `run` does not `defun` a `handle-request` of
  its own — a library defining a function into the user's namespace is
  surprising, and `demo.lisp` would then pin a name only one backend needs.
  Consequence, and the reason `HttpReactorInliner.declaresExport` exists: the
  marker fires for any WASM program that merely quickloads the shim, `clackup`
  called or not — exactly like `HttpLibrary`'s nested-directive detection. For
  the PRE-clackup shape (the user writes the `wasm-export` and calls `handle`
  from it, still supported and still documented) that would append a SECOND
  export named `handle-request`, and a module with a duplicate export name is
  one no engine will compile — measured on V8: `CompileError: Duplicate export
  name 'handle-request'`. So the synthesis is SKIPPED when the program already
  declares that export name itself (`:as` alias or defaulted); the marker is
  lowered either way, because nothing defines it. Pinned by
  `HttpReactorInlinerTest.doesNotSynthesizeWhenTheProgramAlreadyExportsThatName`
  and its `:as` twin.
- **`clackup`'s two `format t` calls are why `--no-wasi` stdout is a sink.**
  Upstream clackup prints a start-up banner (`(and (not use-thread) (not
  silent))` — and `use-thread` defaults to nil on WASM, so it always fires) and
  `clack.handler:run` prints a debug NOTICE (`debug` defaults to `t`). Both are
  in third-party source. The alternatives were making every Worker author pass
  `:silent t :debug nil` (the asymmetry this whole feature exists to remove) or
  binding `*standard-output*` inside `run` (impossible — the banner is printed
  BEFORE `run` is applied), so the fix went where the cause is: under
  `--no-wasi` the `fd_write` stub discards instead of trapping
  (`.kb/wasm-export-no-wasi.md` has the full reason and the output-only rule).
- **The two keywords the example still passes are per-HOST facts, not
  incantation.** `:use-thread nil` because the interpreter and the JVM HAVE
  `:thread-support`, so clackup would otherwise apply `run` — the `*app*` store
  — on another thread and race the next form (on WASM it is already the
  default). `:use-default-middlewares nil` because lack's `backtrace` middleware
  exists to print a report to `*error-output*`, which a reactor does not have;
  it also prints on an error the application CATCHES, and
  `(symbol-value '*error-output*)` is unbound on the compile paths today
  (`.todo/283`), which turns a handled error into a WASM trap. Drop the second
  keyword when `.todo/283` lands.

Why the vendor name is in a shim system rather than in `rontolisp:`: nothing in
the envelope is Cloudflare-specific, but a `rontolisp:`-level function would
have had to be named for the mechanism and would then not be findable by the
people who need it. A handler backend is where the ecosystem already puts
per-host names (`clack-handler-hunchentoot`, `clack-handler-woo`), and it keeps
the core package vendor-free. Pinned by `LispEvaluatorAsdfTest`
(`theCloudflareHandlerShim*`), by `HttpReactorInlinerTest` (the marker lowering
and the synthesized export) and by `examples/cloudflare-workers/httpbin-clack/`,
whose `demo.lisp` runs it on the interpreter, the JVM and wasm-GC
(`examples/examples.yaml`) and whose `worker.lisp` is the deployed Worker.

Measured on the deployed Worker when `clackup` replaced the hand-written
`wasm-export` + `defun` (node 24, same machine, `--no-wasi --optimize`): the
module grew 1,575,467 -> 1,691,678 B raw (342,761 -> 374,424 B gzip, +9%) and
`_initialize` went 23 -> 56 ms (median of five runs each), while the per-request cost did not move
(warm `GET` 0.071 -> 0.068 ms, warm `POST` 0.121 ms both). So `clackup` is a
STARTUP cost on a reactor, not a request cost — which on Cloudflare is paid once
per isolate and reported by `wrangler deploy` (Worker Startup Time 14 -> 25 ms).

## Compile-path enablers that are NOT clack-specific

Landed with this milestone, each with its own pin:

- **Nested/computed `asdf:load-system` / `ql:quickload` compile to call-time
  error stubs** (were compile errors) and **nested/computed
  `asdf:find-system` lowers to args-then-nil**
  (`LispMacroExpander.expandRuntimeFindSystem`): lack's
  `find-package-or-load` has all three inside a defun, guarded by a
  `find-package` probe the baked table answers for every spliced system — the
  asdf calls are dead at run time but must compile. Divergence vs the
  interpreter's live registry (which answers real + builtin systems) is
  deliberate: a compiled program has no system registry and can load nothing.
- **`with-open-file` with a non-native option VALUE (`:if-exists :append`
  etc.) expands to a call-time stub** instead of throwing at expansion —
  lack-middleware-backtrace's file-output branch, dead under the default
  `'*error-output*` output.
- **`FreeVarAnalyzer` walks `typecase`/`etypecase` clause HEADS as type
  specifiers** (keyform + bodies only) in both the free and captured walks —
  the backtrace middleware's `(or pathname string)` head inside a capturing
  lambda used to be read as a variable named PATHNAME. Walked structurally,
  not expanded: the expansion needs the class registry for a class-name head.
  Pinned by `JvmLispCompilerTest.compileAndRunEtypecaseInsideACapturingLambda`.
- **`PATHNAME` joined `PackageRegistry.CL_TYPES`** so `(typecase app ((or
  pathname string) ...))` under `(in-package :clack)` resolves the type name
  to CL's rather than `clack::pathname` (nothing satisfies the pathname type;
  rontolisp pathnames are namestrings).
