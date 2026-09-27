# A built-in's function value reports a wrong count differently from its direct call

Difficulty: Medium

```lisp
(defun report (thunk) (handler-case (funcall thunk) (program-error (e) (princ-to-string e))))
(print (report (lambda () (floor 7 2 3))))
(print (report (lambda () (apply #'floor 7 '(2 3)))))
(print (report (lambda () (apply #'gethash 1 '()))))
```

| | direct `(floor 7 2 3)` | `(apply #'floor 7 '(2 3))` | `(apply #'gethash 1 '())` |
|---|---|---|---|
| interpreter | `FLOOR expects at most 2 arguments, got 3` | `FLOOR expects 1 to 2 arguments, got 3` | `GETHASH expects 2 or 3 arguments, got 1` |
| JVM / wasm (2026-09-27) | same | `Function expects at most 2 arguments, got 3` | `GETHASH expects at least 2 arguments, got 1` |

The direct call reports by `BuiltinCallArity`'s shape on all four. The function value does not:
the interpreter's `LispFunction` says what its own `requireArgCount*` helper spells
(`Environment.requireArgCountBetween`: `1 to 2`), and the compiled wrapper's `&optional` surplus
check (`LambdaLists.tooManyArgsCheck`) says `Function` although `BuiltinFunctionWrappers.arityOperator`
names wrapped built-ins. `.kb/error-handling.md` records the interpreter half as "its function value
still does". A methoded built-in shows it too: the interpreter's dispatcher applies the stashed
`LispFunction` (`1 to 2`), the compile paths' forwarder reports the shape (`at most 2`).

Goal: one text per (built-in, count) whether reached directly, through `#'`/`apply`, or through a
methoded built-in's dispatcher, on all four backends.
