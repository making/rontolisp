# 82: Remove/systematize the "macro-time setf replay" workaround

Status: **workaround in place** (todo 81). Not a bug -- a deliberate hack that
should either be removed once the proper feature lands, or generalized if more
libraries need it.

## What the workaround is

On the compile path, `with-html-output(-to-string)` (cl-who) is expanded at
**macro-expansion time**, and that expansion reads the global `*html-mode*`. So
a top-level `(setf (html-mode) :html5)` must be **replayed into the macro-time
evaluator** (`UserMacroExpander`) for a later render to see the new mode as a
compile-time constant -- a runtime setf cannot affect an already-expanded macro
call.

Current implementation (todo 81):

- `UserMacroExpander.isMacroTimeReplaySetf(form)` recognizes a top-level
  `(setf (PLACE ...) ...)` whose PLACE member name is in a **data file**
  (`src/main/resources/am/ik/rontolisp/eval/macro-time-setf-places.txt`), and
  re-evaluates the expanded form into the macro-time evaluator.
- The data file currently holds one entry: `html-mode`. **No library-specific
  term appears in Java source** -- the earlier hardcoded `"html-mode"` string
  was moved out per review feedback (2026-07-06).

This already avoids "add an `if` branch per function". A new library that needs
the same treatment adds a line to the `.txt` file, not a code change.

## Why it is still a hack

- It only covers the `(setf (PLACE) ...)` shape. A macro-time-effectful
  top-level form of any other shape (a plain function call, `setq` of a
  special, `defparameter` re-run) is not covered generically.
- It silently re-runs the form's side effects into the macro-time evaluator
  (harmless for pure config mutations like html-mode; wrong for a place setter
  with external side effects). There is no way to know a place is pure.
- The root cause is that rontolisp has **no dynamic (special) variable
  binding** and expands library macros at compile time; the "replay" is a
  stand-in for real special-variable semantics.

## Options to resolve

1. **Remove it entirely** once dynamic/special variable binding exists
   (`.todo/54` Phase 4). With true specials, the correct fix is still not a
   runtime setf (the expansion is at compile time), so this may NOT fully go
   away -- but a principled "macro-time configuration" model could replace it.
2. **Generalize the registry** beyond setf: let the data file describe *any*
   top-level operator/place whose forms are replayed at macro time (e.g. a
   small schema: `{ kind: setf-place | operator, name: ... }`). Keep it data,
   never code. If we adopt a data format, prefer one with no new core
   dependency (core libs forbid external deps -- so a line-based `.txt` or a
   reader-parsed `.lisp` data file, NOT snakeyaml/jackson which are test/docs
   scope only).
3. **Model it as compile-time constant folding of specials**: track top-level
   assignments to registered specials during the `UserMacroExpander` pass and
   thread their values into expansion, without re-evaluating arbitrary forms.

## Acceptance

Either the `macro-time-setf-places.txt` mechanism is deleted (feature landed),
or it is replaced by a documented, data-driven "macro-time configuration"
subsystem that no longer needs the setf-only special case -- and cl-who's
`(setf (html-mode) :html5)` still switches mode on all four backends.

Related: `.kb/asdf.md` (cl-who paragraph), `.todo/54` Phase 4 (special vars),
`.todo/76`/`.todo/81` (cl-who), `[[cl-who-loadable]]` memory.
