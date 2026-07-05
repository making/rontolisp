# macrolet

`(macrolet ((name lambda-list body...)...) body...)`

Defines local, lexically scoped macros for the body. Each local macro is expanded within the body exactly like a `defmacro`-defined macro -- its body runs at expansion time with the unevaluated argument forms bound to the parameters -- and the definitions do not leak past the body. Definition lambda lists accept the same shapes as `defmacro` (required parameters plus `&rest`/`&body`, and extended lists via `destructuring-bind`). Local macro bodies see global helper functions but not the surrounding runtime bindings.

On the compile path (`UserMacroExpander`) the whole `macrolet` is expanded away before the JVM/WASM compilers run; the interpreter expands it natively. `macrolet` cannot locally shadow a standard operator (a name in the function namespace); `symbol-macrolet` is not supported.

```lisp
(macrolet ((sq (x) `(* ,x ,x))
           (twice (x) `(+ ,x ,x)))
  (+ (sq 5) (twice 5))) ; => 35
```
