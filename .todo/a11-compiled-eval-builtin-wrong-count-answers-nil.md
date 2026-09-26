# The compiled eval runtime answers a built-in's wrong argument count with nil or a type-error

Difficulty: Medium

Found 2026-09-26 while naming the operator in wrong-count reports. Inside a compiled `eval`
(JVM and wasm alike, before and after that change):

```lisp
(print (handler-case (eval '(apply #'car '(1 2))) (error (c) (list :err (princ-to-string c)))))
(print (handler-case (eval '(car 1 2)) (error (c) (list :err (princ-to-string c)))))
```

| | interpreter | JVM / wasm |
|---|---|---|
| `(apply #'car '(1 2))` | `CAR expects 1 argument, got 2` (`program-error`) | `NIL`, no condition |
| `(car 1 2)` | `CAR expects 1 argument, got 2` (`program-error`) | `CAR: The value 1 is not of type LIST` (`type-error`) |

The same forms OUTSIDE `eval` report the program-error on every backend
(`wrong-arity-funcall-signals-program-error` in `ci-spec.yaml`), so the gap is the eval
runtime's own apply / call path (`JvmEvalRuntimeBuilder`, the wasm `_eval`), which drops or
ignores the surplus argument instead of reaching `_arityChk` / the dispatcher's report.

Goal: the interpreter's condition and text inside a compiled `eval`, pinned in `ci-spec.yaml`.
Read `.kb/error-handling.md` ("A wrong argument COUNT through a function value") first.
