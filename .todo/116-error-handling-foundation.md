 > **Progress 2026-07-12: Phases 1-3 DONE (committed `a8b957b` on develop).**
> Suggested-order items 1-4 all landed: `ByteCodeWriter` exception tables
> (+ v50-no-StackMapTable pin), `unwind-protect` (interpreter/JVM; WASM
> compile error) with the with-* retrofit, condition objects
> (`define-condition` -> defclass expansion with `:report` + seeded built-in
> hierarchy + `error`/`warn`/`signal` designators + `make-condition` +
> `with-slots` + typecase-on-classes), and `handler-case`/`ignore-errors`
> (interpreter + JVM) including the usocket acceptance
> (`usocket:socket-error` catchable, `usocket::%usock-guard` re-signal).
> Full mechanics: `.kb/error-handling.md`. Verified: `./mvnw test` 3320/0 +
> native `CiSpecE2eTest` 788/0 (all four backends).
> **Phase 4 DEFERRED 2026-07-12 after a Step-0 usage survey of the actual
> cl-postgres/Postmodern sources** (see "Phase 4 usage survey" below): the
> verbatim cl-postgres runs correctly WITHOUT restarts -- every
> `restart-case` site there degrades exactly under the existing lite
> primary-form-only lowering, and no vendored patch is needed. The real
> implementation gate is **Postmodern proper** (prepare.lisp / 
> transaction.lisp invoke restarts for real), which todo-115 already scopes
> as a separate follow-up.
> **Remaining**: Phase 4 (gated on Postmodern), a lite `cerror` -> `error`
> lowering (M4/M5 sized, NOT Phase 4 -- see survey), and the Phase 1
> "bonus" (compile-path special-`let` restore on `return`-across-boundary --
> the unwind machinery now exists, `Jvm/WasmLetCompiler` still restore on
> normal exit only). Lite deviations shipped: an uncaught raised `signal`
> aborts instead of returning nil; interpreter `handler-case` catches
> `LispEvalException` only while the JVM catches any `RuntimeException`;
> `(error obj)` ignores the object's class `:report` (type not statically
> known).

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
  format string (the todo-039 stopgap) -- libraries that build error
  hierarchies (cl-postgres `errors.lisp`) load but their semantics are gone;
- the compile path's documented lite limit "a `return`/`return-from`
  unwinding across a special-`let` boundary does not restore the dynamic
  binding" (`.kb/dynamic-special-variables.md`) is the same missing
  machinery: there is no way to attach an on-unwind action to a scope.

Prerequisite for `.todo/115-cl-postgres-support.md` (M3). Supersedes the
"Implementation approach" sketch in `.todo/039-condition-system.md` (kept as
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
- **Condition types**: the CLOS static subset (todo-040, `.kb/clos.md`)
  exists NOW (it did not when todo-039 was sketched) -- `defclass` single
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

### Phase 4 usage survey (Step 0, 2026-07-12) -- DEFERRED, no patch needed

Surveyed the cached `~/.rontolisp/quicklisp/software/postmodern-20260101-git`
sources (cl-postgres v2026-01, the todo-115 target) for every real use of
`restart-case` / `handler-bind` / `invoke-restart` / `find-restart` /
`signal` / `cerror`. Conclusion: **the verbatim cl-postgres needs NO restart
system for M3-M5 -- not even a vendored no-retry patch** -- because the
existing lite `expandRestartCase` (primary form only) is behavior-identical
for it. Phase 4's real gate is Postmodern proper.

**cl-postgres proper (the todo-115 M3-M5 target system):**

- `restart-case`: 4 sites, ALL the shape `(restart-case (error X)
  (clauses...))` -- public.lisp:224 (`initiate-connection`'s `add-restart`,
  the `:reconnect` retry this todo names), public.lisp:311
  (`database-connection-lost` `:reconnect`), public.lisp:373
  (`with-reconnect-restart`'s retry flet, wrapped around `exec-query` &
  friends), sql-string.lisp:29 (ratio-precision `continue` /
  `disable-assertion`). In CL these restart clauses run ONLY when user code
  invokes them via `handler-bind` + `invoke-restart`; **cl-postgres itself
  never does** (zero library-side invokers). So the lite lowering to the
  primary form signals the same error the same way a real CL does when no
  handler invokes the restart -- `handler-case` over `database-error`
  (Phase 3) covers the whole query-round-trip error surface.
- `handler-bind`: exactly ONE real site -- public.lisp:386,
  `wait-for-notification` (LISTEN/NOTIFY), catching
  `postgresql-notification`, which protocol.lisp:130 raises via `warn` (a
  `simple-warning` subclass). Not on the `exec-query` path. `handler-bind`
  is an undefined symbol in rontolisp, so the file LOADS (defun bodies are
  lazy on the interpreter); only a call to `wait-for-notification` fails.
  Document LISTEN/NOTIFY as unsupported until Phase 4.
- `invoke-restart` / `find-restart`: ZERO real sites -- the
  errors.lisp:142-147 grep hits are inside the `database-connection-error`
  docstring (a usage example), not code.
- `cerror`: 4 sites (protocol.lisp:269/289 auth edge + SCRAM signature
  validation, scram.lisp:216/267 input validation) -- all abnormal paths,
  none reached by trust/password auth (the first M5 tiers). `cerror` is
  entirely undefined in rontolisp today. The continue restart is never
  programmatically invoked in cl-postgres, so a **lite `cerror` -> `error`
  lowering (drop the continue-restart format string) is sufficient and is an
  M4/M5-sized follow-up, NOT a Phase 4 dependency.**

**Postmodern proper (explicitly out of todo-115 scope) -- the REAL Phase 4
customer:**

- prepare.lisp:54-66 (`generate-prepared`): every `defprepared` call runs
  under nested `handler-bind`s that `(invoke-restart :reconnect)` on
  `database-connection-error` / `admin-shutdown` and auto-reset on
  `invalid-sql-statement-name` / `duplicate-prepared-statement` -- the hot
  path of the prepared-statement API, plus prepare.lisp:289
  (`reset-prepared-statement` invokes `'reset-prepared-statement`).
- transaction.lisp:63-66 (`retry-transaction`): `find-restart` +
  `invoke-restart` exposed as user API over the transaction.lisp:70
  `restart-case`.
- json-encoder.lisp:125/282-288 (`with-guessing-encoder`:
  `restart-case` + `handler-bind` + `(invoke-restart 'try-as-alist)`),
  connect.lisp:58, roles.lisp:276, execute-file.lisp:392.

**Decision**: defer Phase 4 until Postmodern support starts (its own todo,
per todo-115's closing note). When it does, the implementation sketch above
stands, with one addition from the survey: the Postmodern shapes need
restarts ESTABLISHED in one function and INVOKED from a handler running
before unwinding (handler-bind), plus `find-restart` returning a
first-class restart object -- i.e. handler-bind and the restart stack must
land together; restart-case alone unblocks nothing real.

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
