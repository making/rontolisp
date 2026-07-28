# Restart system: handler-bind + restart stack + find-restart/invoke-restart

Goal: the full "Phase 4" of the condition system from `.kb/error-handling.md`
(lines ~354-405 carry a 2026-07-12 survey of exactly the postmodern tree this
unblocks). These pieces must land TOGETHER on all backends -- the survey's
conclusion stands: restarts must be ESTABLISHED in one function and INVOKED
from a handler running BEFORE unwinding, with `find-restart` returning a
first-class restart object, so `restart-case` alone unblocks nothing real.

Blocks: `.todo/202-postmodern-non-mop-milestone.md` (this is the single
largest language gate for postmodern proper).

## What postmodern needs, site by site

- `postmodern/prepare.lisp` (`generate-prepared`): THREE nested `handler-bind`
  layers around `cl-postgres::with-reconnect-restart`; handlers do
  `(invoke-restart :reconnect)` (a KEYWORD-named restart) and call
  `#'reset-prepared-statement`, which itself does
  `(invoke-restart 'reset-prepared-statement)` -- a restart established inside
  cl-postgres, invoked from postmodern. This is the hot path of the
  prepared-statement API, not an error corner.
- `postmodern/transaction.lisp` (`call-with-transaction`): `tagbody start`
  around a `restart-case` whose `retry-transaction` clause body is `(go start)`
  -- a `go` OUT of a restart handler back into the enclosing tagbody, wrapped
  around `unwind-protect` + `multiple-value-prog1`. `retry-transaction` (user
  API) does `find-restart` with a condition argument then `invoke-restart` on
  the returned OBJECT.
- `postmodern/connect.lisp` (`connect-toplevel`): `restart-case` with two
  restarts carrying `:report` strings; one does `return-from`.
- `postmodern/roles.lisp`: a restart taking 5 arguments.
- `postmodern/execute-file.lisp`: a restart with a parameter, a `:report`, and
  an `:interactive` lambda calling `read-line`.
- `postmodern/json-encoder.lisp`: `restart-case` + `handler-bind` +
  `(invoke-restart 'try-as-alist)` pairs (`with-substitute-printed-
  representation-restart`, `encode-json-list-guessing-encoder`).
- `postmodern/roles.lisp:272`: `cerror` -- today lowered to `error` with the
  continue-format dropped; real `cerror` needs the `continue` restart.

## Current state (2026-07-28)

- `handler-bind`: interpreter defers to call time (loads, dies if run);
  compilers emit `LispMacroExpander.handlerBindStub()` = unconditional error.
- `restart-case`: lite no-op keeping only the primary form.
- `invoke-restart` / `find-restart` / `restart-bind` / `with-simple-restart` /
  `compute-restarts` / `abort` / `continue` / `muffle-warning`: absent.
- Residual catalog: `.todo/039-condition-system.md`. Existing machinery to
  build on: typed `handler-case` and the `%error-runtime` chunked tables
  (`.kb/error-handling.md`).

## Required semantics (minimum honest subset)

1. A dynamic restart stack: `restart-case`/`restart-bind` push named restart
   records (name, function, report, interactive, test) for their dynamic
   extent; must survive re-entry through handlers.
2. `handler-bind`: handlers run in the SIGNALING dynamic environment (before
   unwinding), so an invoked restart transfers control non-locally to the
   establishing `restart-case`. Declining (handler returns) resumes the search.
3. `find-restart` (name-or-object, optional condition) returning a first-class
   restart object; `invoke-restart` accepting name, keyword, or object, with
   arguments.
4. `restart-case` clause bodies as non-local exits (the `(go start)` shape
   means the clause body runs AFTER unwinding to the restart-case frame, in
   its lexical environment -- the standard model).
5. `cerror`/`continue`, `abort`, `with-simple-restart` on top.

Wasm note: any program with these forms is already in EH mode
(`-W exceptions=y`); the restart transfer should reuse the existing
block-exit/condition throw machinery, not invent a second one.

## Out of scope

- Interactive debugger integration (`*debugger-hook*`, `break`); the
  `:interactive` lambda only needs to work when a restart is invoked
  interactively, which nothing in postmodern's library code does -- store it,
  don't wire a debugger.
