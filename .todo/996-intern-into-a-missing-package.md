# intern into a missing package: uncatchable on the interpreter, silently created when computed

Difficulty: Medium

CLHS `intern` signals an error when the package does not exist. Measured 2026-09-26, each form
its own program:

```lisp
(print (handler-case (intern "X" "NOPKG") (error (e) :caught)))
(defun f (p) (intern "X" p))
(print (handler-case (f "NOPKG") (error (e) :caught)))
```

| | literal package | computed package |
|---|---|---|
| interpreter | load fails, `error: No such package: NOPKG`, exit 1 | same |
| JVM, wasm-GC Preview 1, `--component` | `:CAUGHT` | `NOPKG:X` -- the package is created |

The interpreter's `LispPackageException` never reaches `evalHandlerCase` as a condition; the
compiled backends lower the literal case to `(error "No such package: ...")`
(`LispMacroExpander`, `textDatum("No such package: " + pkg)`) but let the computed case create
the package. `find-symbol` answers `NIL` for a missing package on all four (also not CLHS). Make
both `intern` shapes a catchable run-time error on every backend and pin the lines above in
`ci-spec.yaml`.
