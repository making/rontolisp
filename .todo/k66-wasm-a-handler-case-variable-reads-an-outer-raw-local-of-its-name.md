# k66. wasm: a handler-case variable reads an outer raw local of its name

Difficulty: Medium

Found by k56 (2026-11-20), on the build before it too. `WasmHandlerCaseCompiler` binds a
clause's variable by putting it in `Ctx.locals` alone; an outer binding of the same name that
the backend keeps raw (`Ctx.rawLocals`: a counted, exact or dual local,
`.kb/wasm-unboxed-locals.md`) is looked up first by every reader, so the clause reads the
outer local. The same holds for an outer declared range or array kind
(`Ctx.declaredIntegers`, `Ctx.declaredArrays`), which every other binder shadows.

```lisp
(defun hc (n)
  (let ((e 0))
    (dotimes (i n) (setq e (logand (+ e i) 255)))
    (handler-case (error "boom")
      (error (e) (princ-to-string e)))))
(print (hc 5))   ; the interpreter and the JVM: "boom"; wasm: "10"
```

- **To do**: a failing test first (`WasmLispCompilerIntegrationTest`, or a ci-spec case for
  the four backends), then shadow the three maps for the clause's variables -- the error
  clauses, `:no-error`'s values -- as `WasmLambdaCompiler` does for an inline lambda's
  parameters. Look for any other binder that puts a name in `Ctx.locals` without them.
