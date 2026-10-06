# d80. A system-signalled undefined-function has no cell-error-name

Difficulty: Medium

On all four backends the `undefined-function` an unbound call / `symbol-function` / `apply`
signals carries no `name` slot, so `cell-error-name` answers NIL; SBCL answers the name.

```lisp
(handler-case (funcall (intern "NOPE"))
  (undefined-function (c) (cell-error-name c)))   ; SBCL: NOPE, rontolisp: NIL
```

## Plan

- Failing four-backend fixture first.
- Fill the `name` slot wherever the condition is made: the interpreter's signal, the JVM's
  `emitUndefinedFunctionThrow` / runtime throw, WASM `WasmRuntimeBuilder.NotFunctionReport`
  (dispatchers and `_undefined_function`) and the call-time stub
  (`LispMacroExpander.undefinedFunctionCallStub`, today a `simple-error` by message).
