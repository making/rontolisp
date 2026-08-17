# 426. `ctypecase` does not exist

Difficulty: Low

```lisp
(ctypecase x (string :s) (list :l))
;; rontolisp: The function <PKG>::CTYPECASE is undefined   -- on every backend
```

`case`, `ecase`, `ccase`, `typecase` and `etypecase` all ship;
`ctypecase` is the one missing member of the family. The name is not in
`LispNames` nor in `PackageRegistry.CL_SYMBOLS`, so it does not even resolve to
`cl:` -- a caller gets the undefined-function error above, not a "not
implemented" one. `format.IndentRules` already lists `ctypecase` beside the
other five, so the formatter lays it out correctly for a form nothing can run.

Found by the cl-mustache spike (`.todo/425`): `ensure-context` is a
`ctypecase` over `list` / `hash-table` / `context`, and it is the first thing
every entry point calls, so the whole library was dead on the first render.
Swapping it for `etypecase` in a scratch copy was enough to unblock the spike,
which is the measure of how narrow this is.

`ctypecase` is to `etypecase` exactly what `ccase` is to `ecase`: the same
exhaustive type dispatch, except the no-clause-matched error is CORRECTABLE
through a `store-value` restart that re-tests the new value against the
clauses. `LispMacroExpander.expandCcase` is therefore the template to copy, and
the shared `case`/`ecase`/`ccase`/`typecase`/`etypecase` route
(`LispMacroExpander:107`, and its two sibling switch sites) is where the arm
goes -- shared, so all four backends move together with no per-compiler class.

## Definition of done

`ctypecase` works on all four backends, with the `store-value` restart looping
until a clause matches (`ccase`'s own restart shape). `LispNames` +
`PackageRegistry.CL_SYMBOLS`, `expandCtypecase` in `LispMacroExpander`, and the
`LispEvaluator` / `JvmExprCompiler` / `WasmExprCompiler` cases routed through
it. Pinned in `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest` plus a `ci-spec.yaml` case, with the doc page
(`reference/macros/ctypecase.md` en+ja), the `_catalog.yaml` entry and the
`reference/functions.md` row beside `ccase`. `IndentRules` needs nothing -- the
entry is already there.
