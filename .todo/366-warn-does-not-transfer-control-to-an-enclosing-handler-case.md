# `warn` does not transfer control to an enclosing `handler-case` clause

Difficulty: Medium

```lisp
(handler-case (warn "w1") (warning (c) (list :caught (princ-to-string c))))
;; CL:        (:CAUGHT "w1")
;; rontolisp: prints "WARNING: w1" and answers NIL
```

`signal` of the same condition IS caught:

```lisp
(handler-case (signal 'simple-warning :format-control "w2" :format-arguments nil)
  (warning (c) (list :caught c)))          ; => (:CAUGHT #<...>)   -- correct
```

So the gap is `warn`'s own lowering, not the handler machinery.
`LispMacroExpander.expandWarn` runs the handler-bind hook (`%run-handlers`) at
the signal point -- which is why `handler-bind` + `muffle-warning` works -- but
never throws the `handler-case` block-exit, so the clause is skipped and `warn`
returns nil after printing its report. Per CLHS 9.1.4.1 `warn` SIGNALS the
condition, and an enclosing `handler-case` clause for `warning` is a
control transfer like any other; only when no handler takes it does `warn` print
to `*error-output*` and return nil.

Fix in `expandWarn` (shared, so all four backends move together), and check
`cerror`/`signal`'s own arms while there. Pin in the restart/condition blocks of
`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` and
one `ci-spec.yaml` case, then note it in `.kb/error-handling.md`'s "Lite
deviations" list (which currently does not mention it).

**Found 2026-08-14 writing `.todo/354`'s tests**: `uiop:style-warn` under
`(handler-case ... (style-warning ...))` looked broken and was not -- the same
program under `handler-bind` + `muffle-warning` behaves correctly on all four
backends, which is what those tests use instead. Nothing in uiop depends on the
`handler-case` shape, so this is its own item.
