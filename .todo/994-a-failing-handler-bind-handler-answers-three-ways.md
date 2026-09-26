# A `handler-bind` handler that fails in a built-in reports a different condition on each backend

Difficulty: Medium

Measured 2026-09-26:

```lisp
(defun k ()
  (handler-bind ((error (lambda (c)
                          (print c)
                          (car c))))
    (error "first")))
(k)
```

- Interpreter: prints `c` once, then `Unhandled condition: CAR: The value #<SIMPLE-ERROR ...> is
  not of type LIST` -- SBCL's answer: the handler runs with its own `handler-bind` disabled
  (CLHS 9.1.4.1), so its `car` failure has no handler.
- JVM: prints `c` once, then `Unhandled condition: first` -- the handler's failure is lost and the
  original condition escapes.
- wasm-GC (Preview 1 and `--component`): prints `c`, then the handler RUNS AGAIN on the `car`
  failure's `type-error` (the `%hb-guard` landing pad walks the cluster stack for a built-in's
  failure, the failing handler's own cluster included), whose `car` then fails in turn:
  `Unhandled condition: CAR: The value #<TYPE-ERROR :DATUM #<SIMPLE-ERROR ...> ...> is not of type
  LIST`.

Goal: the interpreter's answer on every backend, pinned in `ci-spec.yaml` (the output) and its
`standalone:` list (the report).

Read first: `.kb/error-handling.md` ("Phase 4 -- handler-bind + the restart stack": the
`%hb-guard` pad), `codegen/wasm/WasmHandlerCaseCompiler.compileGuard`,
`LispMacroExpander.expandHandlerBind`.
