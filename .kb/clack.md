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
