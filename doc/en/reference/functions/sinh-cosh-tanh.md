# sinh cosh tanh

`(sinh number)` `(cosh number)` `(tanh number)`

The hyperbolic functions, each returning a float. `sinh` is the hyperbolic sine, `cosh` the hyperbolic cosine, and `tanh` the hyperbolic tangent. `tanh` works on all three backends: the interpreter and JVM use `Math.tanh`, while the WASM backend derives it from its software `exp` approximation, so its result may differ slightly in the least significant digits. `sinh` and `cosh` are available on the interpreter and JVM backends only (not WASM).

```lisp
(tanh 0) ; => 0.0
```
