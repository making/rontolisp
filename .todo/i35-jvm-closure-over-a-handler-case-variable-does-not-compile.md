# i35. JVM: a closure over a `handler-case` clause variable does not compile

Difficulty: Low

Measured 2026-11-04. Interpreter, WASM and SBCL 2.2.9 run it; the JVM refuses the program, on a jar from
before h93 as well.

```lisp
(defun run (f) (funcall f))
(defun check-e ()
  (handler-case (error "x")
    (error (c) (run (lambda () (print (type-of c)))))))
(check-e)
```

```
error: while compiling defun CHECK-E: closure over C whose binding left it unboxed: the capture
  analysis and the closure emitter disagree about this name (FreeVarAnalyzer.findCapturedVars)
```

`JvmLambdaCompiler` throws this on purpose when a binder did not ask `FreeVarAnalyzer.findCapturedVars`.
`JvmHandlerCaseCompiler` binds the clause variable to a plain slot and removes the name from `boxedVars`
without asking. Check the `:no-error` clause's variables too, and `WasmHandlerCaseCompiler` for how it
already works there.

Consumer: log4cl compiled to a class stops here now that `with-slots` no longer does:
`property-configurator.lisp:322`, `(handler-case ... (property-parser-error (c) (log-error ... c)))`, where
`log-error` wraps its arguments in a lambda. Interpreter: loads and logs. Pin in ci-spec.
