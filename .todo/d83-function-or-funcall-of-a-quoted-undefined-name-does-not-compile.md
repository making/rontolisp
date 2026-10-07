# d83. #'name and (funcall 'name) of an undefined name do not compile

Difficulty: Medium

```lisp
(print (handler-case (funcall 'nope 1) (undefined-function (c) (cell-error-name c))))
(print (handler-case #'nope (undefined-function (c) (cell-error-name c))))
```

SBCL and the interpreter print `NOPE` twice; the JVM, P1 and the component refuse the program
with `Cannot compile: NOPE` (the JVM compiles the `funcall` inside a `lambda`;
`JvmFunctionFormCompiler.compileNamed` /
`WasmFunctionFormCompiler.compileNamed`). A direct call of the same name compiles to a call-time
stub with a warning (`.kb/error-handling.md`, "Undefined functions keep the call-time stub
contract"); a function reference should keep the same late binding.

## Plan

- Four-backend fixture first.
- In `compileNamed`'s last arm: warn and compile the throw the direct call's stub reaches
  (`JvmFunctionFormCompiler.emitUndefinedFunctionThrow(String, ctx)`; wasm
  `_undefined_function` where present), at the point the reference is evaluated.
