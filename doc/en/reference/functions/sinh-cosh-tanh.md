# sinh cosh tanh

`(sinh number)` `(cosh number)` `(tanh number)`

The hyperbolic functions, each returning a float. `sinh` is the hyperbolic sine, `cosh` the hyperbolic cosine, and `tanh` the hyperbolic tangent. All three work on every backend, fdlibm's on each (`StrictMath` on the interpreter and the JVM, the same algorithm on WASM), so the digits agree everywhere. `(sinh 0)` is exactly `0.0` and `(cosh 0)` exactly `1.0` everywhere.

```lisp
(tanh 0) ; => 0.0
```
