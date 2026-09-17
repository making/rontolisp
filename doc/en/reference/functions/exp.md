# exp

`(exp number)`

Returns e raised to the power of `number` as a float. Every backend computes it with fdlibm (`StrictMath.exp` on the interpreter and the JVM, the same algorithm as a runtime function on WASM), so the digits agree everywhere: `(exp 1.0)` is `2.7182818284590455`.

```lisp
(exp 0) ; => 1.0
```
