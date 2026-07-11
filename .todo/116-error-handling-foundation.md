# Error-handling foundation: unwind-protect + typed conditions + handler-case

The root cause behind every "lite" error-path compromise shipped so far:
rontolisp can SIGNAL a fatal error (`error` -> `%error`) but cannot CLEAN UP
on a non-local exit (`unwind-protect` is absent) and cannot CATCH by type
(no condition objects, no `handler-case`). Concrete symptoms:

- the usocket `with-*` macros close their socket on **normal exit only** (a
  body error leaks the handle) -- same for `with-open-file` /
  `with-output-to-string` / `with-input-from-string`, whose expansions have
  the identical close-after-body shape;
- `usocket:socket-error` (and the whole usocket condition hierarchy) is
  registered as a **data symbol only** -- a connection failure cannot be
  caught, so any portable CL code with `(handler-case (socket-connect ...)
  (usocket:socket-error ...))` dies;
- `define-condition` is a parsed no-op and `make-condition` collapses to its
  format string (the todo-39 stopgap) -- libraries that build error
  hierarchies (cl-postgres `errors.lisp`) load but their semantics are gone;
- the compile path's documented lite limit "a `return`/`return-from`
  unwinding across a special-`let` boundary does not restore the dynamic
  binding" (`.kb/dynamic-special-variables.md`) is the same missing
  machinery: there is no way to attach an on-unwind action to a scope.

Prerequisite for `.todo/115-cl-postgres-support.md` (M3). Supersedes the
"Implementation approach" sketch in `.todo/39-condition-system.md` (kept as
the API-surface catalog); written against the 2026-07-11 codebase.

## Substrate facts (verified)

- **Interpreter** already has both unwind channels as Java exceptions:
  `LispEvalException` (errors) and `LispReturnSignal` (`%block`/`return`
  non-local exit), and a precedent for scope-exit actions -- the dynamic
  (special) binding restore runs in a `finally` on every exit path
  (`DynamicBindings`). `unwind-protect` on the interpreter is literally
  `try { body } finally { cleanup }`.
- **JVM backend**: `%error` (`JvmErrorCompiler`) throws; JVM class version 50
  supports exception tables WITHOUT StackMapTable (the lenient verifier we
  already rely on -- catch blocks do not add stack-map obligations, unlike
  v51+). `am.ik.jvm`'s `JvmClassShaker` already parses/rewrites Code
  exception tables, but **`ByteCodeWriter`'s method emission writes an empty
  exception table** -- adding `(start, end, handler, catch_type)` entries to
  the method-writing API is the enabling assembler work item.
- **WASM**: `%error` compiles to `unreachable` -- a trap, uncatchable, no
  message (`WasmErrorCompiler`). wasmtime 46 gates the exception-handling
  proposal behind `-W exceptions=y` (off by default). Catching on WASM means
  the exnref EH proposal (new run flag, adapter interactions) OR a
  sentinel-return threading transform (every call site checks -- invasive).
  **Decision: WASM is out of scope for catching in v1** -- `handler-case` /
  `unwind-protect` = compile error on the WASM path (the TLS gating
  precedent), revisit as its own todo once wasmtime's EH is on by default.
- **Condition types**: the CLOS static subset (todo-40, `.kb/clos.md`)
  exists NOW (it did not when todo-39 was sketched) -- `defclass` single
  inheritance over tagged lists + `generateDispatcher` type tests +
  `makeTypeTest` (`typep`/`typecase`) are exactly the pieces a condition
  hierarchy needs; `define-condition` can become a `LispMacroExpander`
  expansion onto the `%class` machinery instead of a no-op.

## Phase 1 -- `unwind-protect` (interpreter + JVM; the with-* fix)

`(unwind-protect protected cleanup...)`: cleanup runs on normal return, on
`error` unwind AND on `return`/`return-from` (`LispReturnSignal` /
compile-path `%block` exit).

- Interpreter: new special form; `try { eval protected } finally { eval
  cleanups }` -- rethrow after cleanup; a cleanup that itself signals
  replaces the pending unwind (document; CL says the new one wins).
- JVM: `ByteCodeWriter` exception-table support (catch_type 0 = any) +
  `JvmUnwindProtectCompiler`: protected body, GOTO done; handler: run
  cleanup, ATHROW; done: run cleanup, push result. The `%block` `return`
  channel on the JVM -- verify how `Jvm` lowers `return` (branch vs.
  exception); if it is a plain branch out of the loop/method, route it
  through the cleanup block too (the same "empty operand stack" constraint
  `%block` already imposes helps here).
- WASM (incl. `--no-gc`): compile error "unwind-protect is not supported on
  the WASM backend" (traps are uncatchable; nothing to clean up after).
- Retrofit in the SAME change: `expandWithOpenFile` /
  `expandWithOutputToString` / `expandWithInputFromString` and the four
  usocket `expandUsocketWith*` expansions switch their close-after-body
  shape to `unwind-protect` **behind a backend check** (the expansions are
  shared; either keep the old shape on WASM or make the expansion
  backend-parameterized). Fixes the todo-114 "closes on normal exit only"
  limitation and deletes those doc caveats.
- Bonus (same machinery): compile-path special-`let` restore on
  `return`-across-boundary (`.kb/dynamic-special-variables.md` lite limit)
  becomes fixable -- file as a follow-up inside this phase if cheap.

## Phase 2 -- condition objects + typed `error` (the socket-error fix)

- A condition is a CLOS-subset instance: `define-condition` = real
  `LispMacroExpander` expansion onto `expandDefclass` (`:report` becomes a
  method/format lambda; parent defaults to `condition`); seed a built-in
  hierarchy `condition` > `serious-condition` > `error` > (`simple-error`,
  plus hooks for user types); `warning`/`simple-warning`.
- `error` gains the CL designator API: `(error 'type :initarg v ...)` and
  `(error condition-object)` construct/carry the instance;
  `(error "fmt" args...)` builds a `simple-error`. Interpreter:
  `LispEvalException` grows a `LispVal condition` slot. JVM: the emitted
  runtime exception carries the tagged-list instance.
- `typep`/`typecase` on conditions ride the existing `makeTypeTest` + CLOS
  class tests; `signal`(non-fatal -> nil if unhandled), `warn`
  (print + continue) come along nearly free.
- Retrofit: `SocketSupport`/fetch/stream errors signal typed conditions
  (`usocket:socket-error`, `connection-refused-error` mapped in
  usocket.lisp; keep messages identical -- ci-spec pins error text).

## Phase 3 -- `handler-case` / `ignore-errors` (interpreter + JVM)

- `(handler-case expr (type (var) handler...)... (:no-error ...))`:
  interpreter = `try/catch (LispEvalException e)` + `typep` dispatch on the
  carried condition, rethrow on no match; JVM = exception-table catch of the
  emitted condition-carrying exception class + the same typep dispatch;
  `ignore-errors` = sugar over `(handler-case ... (error (c) (values nil
  c)))`. WASM = compile error (v1).
- Acceptance: the cl-postgres/Postmodern shape works on interpreter + JVM:
  `(handler-case (usocket:socket-connect "127.0.0.1" closed-port)
     (usocket:socket-error (e) :refused))` => `:refused`.

## Phase 4 -- `handler-bind` + `restart-case`/`invoke-restart` (cl-postgres M3 tail)

Needed by cl-postgres's `initiate-connection` retry (`restart-case` +
`add-restart`). handler-bind = a dynamic handler stack (the `DynamicBindings`
thread-local pattern) consulted by the signaling path before unwinding;
restart-case = named restart points implemented over the Phase 1/3 unwind
machinery (invoke-restart = a targeted non-local exit, the
`LispReturnSignal` shape with a restart tag). Interpreter-first; JVM if the
exception-table approach extends cleanly; defer if cl-postgres turns out to
work with a vendored no-retry patch.

## Cross-cutting

- **Backend contract docs**: CLAUDE.md bullet + `.kb/` page (new
  `.kb/error-handling.md`), the "with-* close on normal exit only" caveats
  in `doc/{en,ja}` (guide + usocket macro page) get replaced, packages page
  usocket bullet updated.
- **ci-spec**: error-text pins exist today; typed conditions must not change
  the printed output of an UNCAUGHT error on any backend (the interpreter's
  top-level printer keeps the same `Error: message` shape). Add
  `handler-case`/`unwind-protect` cases with `expectedByBackend` (WASM =
  compile error) only if the driver supports per-case backend skips --
  otherwise pin in the three per-backend test suites.
- **First-class**: `handler-case`/`unwind-protect` are special forms /
  macros (no `#'`), consistent with `PackageRegistry.specialOperatorNames`.
- Out of scope: `break` (needs REPL integration), interactive restarts,
  `storage-condition`, WASM catching (future todo once wasmtime enables EH
  by default).

## Suggested order & checkpoints

1. `ByteCodeWriter` exception-table emission + a raw am.ik.jvm unit test.
2. Phase 1 unwind-protect + with-* retrofit (`LispEvaluatorTest` /
   `JvmLispCompilerTest`: cleanup runs on error AND on `return`; leak test =
   the todo-114 `usocketWithConnectedSocketClosesOnNormalExit` gains an
   error-path sibling).
3. Phase 2 conditions (+ typed SocketSupport signals; ci-spec error-text
   unchanged, native E2E).
4. Phase 3 handler-case (+ the usocket acceptance test above; unblocks
   `.todo/115` M3).
5. Phase 4 restarts, driven by cl-postgres's actual needs.
