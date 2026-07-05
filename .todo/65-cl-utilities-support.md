# 65: Load cl-utilities via asdf:load-system

Follow-up of the real-library loading campaign (split-sequence and
parse-number load on all four backends, see `.kb/asdf.md` and
`examples/asdf/`). cl-utilities (public domain, the classic grab-bag:
`with-unique-names`, `once-only`, `compose`, `extremum`, `collecting`, its own
`split-sequence`, ...) was triaged 2026-07-05 and is blocked on three
features; everything else it uses already works.

## Blockers (in dependency order)

1. **`macrolet`** (`compose.lisp` uses it inside a function body to stamp out
   repeated forms) -- the real feature, `.todo/34`'s leftover. The
   flet/labels precedent applies: a `LispMacroExpander` lowering that
   registers the local macros and expands their call sites within the body
   walk (the machinery in `UserMacroExpander`/`rewriteLocalCalls` already
   knows how to walk bodies with local shadowing).
2. **`restart-case`** (`read-delimited.lisp` wraps its bounds errors:
   `(restart-case (error 'read-delimited-bounds-error ...) (continue ...))`).
   A LITE lowering matching our no-restart reality: expand to the primary
   form only (the restart clauses are dead without a condition system), so
   the error simply signals. Document like `check-type`'s lite semantics.
3. **`define-compiler-macro`** (`compose.lisp`, `split-sequence.lisp`,
   `expt-mod.lisp`) -- an optimization hint; a parsed no-op returning nil is
   correct (the ordinary function definition remains authoritative), same
   pattern as `declaim`/`deftype`.

Also present but already handled: `define-condition` (lite no-op),
`&whole` in the compiler macros (moot once they are no-ops).

## Plan

- Ship 2 and 3 as cheap lite expansions any time; 1 (`macrolet`) is the real
  session-sized unit and also unblocks other libraries.
- Then apply the standard workflow: driver on the interpreter, fix residue,
  verify all four backends, vendor + `ClUtilitiesE2eTest` + a ci-spec case
  for whatever new residue surfaces, docs (asdf guide "what can I actually
  load" + examples/asdf).
- `anaphora` needs `symbol-macrolet` + `define-setf-expander` (harder than
  macrolet: a walker that substitutes VARIABLE references); consider after
  macrolet lands since the walkers overlap.
