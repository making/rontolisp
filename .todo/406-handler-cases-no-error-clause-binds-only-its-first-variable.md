# 406. `handler-case`'s `:no-error` clause binds only its first variable

Difficulty: Medium

Found while checking the sibling forms of `.todo/397` (an `unwind-protect`
cleanup clobbering the protected form's secondary values). The `:no-error`
clause of `handler-case` receives the protected form's VALUES -- it is a
multiple-value consumer, the one clause shape that binds a lambda list rather
than a condition variable. Ours binds the first variable to the primary value
and leaves the rest UNBOUND:

```lisp
(handler-case (values 1 2 3) (:no-error (a b c) (list a b c)))
; => The variable B is unbound       expected (1 2 3)
```

All three backends do the same thing, for the same reason: each one copied the
"bind `(car varlist)` to the value" shape of an error clause.

- interpreter: `LispEvaluator.evalHandlerCase`, the `noErrorClause` tail --
  `clauseParts.get(1) instanceof LispCons varList && varList.car() instanceof
  LispSymbol var` defines exactly one name.
- JVM: `JvmHandlerCaseCompiler` (the `":NO-ERROR"` branch).
- WASM: `WasmHandlerCaseCompiler` (same branch).

## The work

- Make the clause a real consumer of the protected form's values: the same
  `%mv-spill` route `multiple-value-bind` uses (`.kb/multiple-values.md`), so a
  `(values ...)` tail, a `values-list` and a producing CALL all spread. A
  missing value is nil and a surplus one is dropped, as in
  `multiple-value-bind`.
- The clause's variable list is a full lambda list in CL (`&optional`/`&rest`
  are legal there). Decide whether to desugar it through `LambdaLists` or to
  accept the required-only shape and signal on the rest -- and say which in the
  `.kb` file.
- Pin on all four backends (the ci-spec case too), and extend the
  `.kb/multiple-values.md` sibling paragraph that currently records this gap.
