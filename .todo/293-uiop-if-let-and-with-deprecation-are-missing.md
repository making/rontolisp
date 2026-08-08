# `uiop:if-let` and `uiop:with-deprecation` are missing

Difficulty: Low

Found 2026-08-08 while spiking tiny-routes (`.todo/291`). Two UIOP macros a
library may reasonably spell are absent from our `uiop` package
(`PackageRegistry`'s `uiopExternals` / `LispNames`), and the first of them is a
LOAD-time failure, not a call-time one:

```console
$ echo '(ql:quickload "tiny-routes")' > t.lisp && rontolisp t.lisp
LispPackageException: The symbol WITH-DEPRECATION is not external in the UIOP
package (use UIOP::WITH-DEPRECATION)

$ rontolisp app.lisp                     # after the above is fixed
LispEvalException: The function UIOP::IF-LET is undefined
```

`if-let` resolves (it arrives via `(:import-from :uiop #:if-let)`, which does not
check externality) but has no definition, so the failure lands at the call.

## What they are

- **`uiop:if-let`** -- upstream's own copy of alexandria's:
  `(if-let ((a X) (b Y)) then [else])` binds like `let` and takes the `then`
  branch when EVERY variable is non-nil. A single un-nested binding
  `(if-let (a X) ...)` is also accepted. Its siblings `when-let` and `when-let*`
  are exported by real UIOP too and are equally cheap; add all three rather than
  leaving the next library to re-open this item.
- **`uiop:with-deprecation`** -- `(with-deprecation (LEVEL) definitions...)`
  marks the definitions it wraps as deprecated so that a caller gets a style
  warning. rontolisp has no deprecation-warning machinery and no compile-time
  warning channel to route one through, so the honest lowering is
  `(progn ,@definitions)`: the definitions are established exactly as written and
  the level form is ignored. Say so in the doc page -- a silently-dropped
  diagnostic that is not documented reads as a bug later.

## Where

The four-backend wiring is the standard "Adding a New Macro" path in `CLAUDE.md`:
a name in `LispNames`, the member in `PackageRegistry`'s `uiopExternals`, an
`expand*` in `LispMacroExpander` (shared), and the dispatch case in
`LispEvaluator.evalCons` plus `JvmExprCompiler` / `WasmExprCompiler`. The
existing `uiop:with-temporary-file` wiring is the closest precedent -- it is the
one other UIOP MACRO, and it is matched on the qualified name the same way.

Note `with-deprecation` wraps top-level DEFUNs in the wild (that is exactly the
tiny-routes shape, inside an `eval-when`), so the expansion must stay a
top-level-splicing `progn` -- not a `let`-like form that would bury the defuns.

## Done when

- `(uiop:if-let ((a 1) (b 2)) (list a b) :none)`, the single-binding spelling, the
  all-must-be-non-nil rule, and `when-let` / `when-let*` answer identically on
  all four backends.
- A `with-deprecation` block of `defun`s defines every function in it, on all
  four backends, from a top level and from inside `eval-when`.
- Per-operator doc pages under `doc/{en,ja}/reference/macros/` with `_catalog.yaml`
  entries, and the `uiop` row in `doc/{en,ja}/guides/asdf-systems.md`'s built-in
  shim table -- which today enumerates exactly which UIOP members have real
  definitions -- updated in the same commit.
- `format/IndentRules` entries for all four: every one of them takes a BODY after
  a fixed head (`with-deprecation` after its level list, the `*-let` family after
  the binding list), so without a rule `rontolisp format` lays them out as
  function calls and aligns the body under the first argument
  (`.kb/formatter.md`).
