# A restart-case inside a lambda does not compile once the program binds handlers

Difficulty: Medium

Found 2026-09-26 while doing a33 (a `restart-case` inside a `java:call` callback):

```lisp
(handler-bind ((error (lambda (c) (invoke-restart 'use-value 100))))
  (print (funcall (lambda () (restart-case (error "bad") (use-value (v) v))))))
```

| interpreter | JVM | wasm-GC |
|---|---|---|
| `100` | compile error `Cannot capture variable: %MV-SPILL` (`JvmLambdaCompiler`) | compile error `Cannot find variable for closure: %MV-SPILL` |

The same `restart-case` in a lambda compiles in a program without `handler-bind` and `error`
(`(mapc (lambda (x) (restart-case x (use-value (v) v))) ...)` prints what the interpreter does),
and a `restart-case` outside any lambda compiles with them. So the restart-mode expansion names
`%mv-spill` inside the closure where the closure analysis finds no binding of it. Not yet
established: which expansion emits the reference, and whether `LispMacroExpander.injectMvSpillGlobal`
decides before that expansion runs or its scan does not reach into the lambda -- measure that
first.

Pin: `ci-spec.yaml` cases with the program above and with the `restart-case` in a `mapc` lambda,
on all four backends.
