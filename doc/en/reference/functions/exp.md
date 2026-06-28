# exp

`(exp number)`

Returns e raised to the power of `number` as a float. The interpreter and JVM backends compute it with `Math.exp`; the WASM backend uses a software approximation, so its result may differ slightly in the least significant digits.

```lisp
(exp 0) ; => 1.0
```
