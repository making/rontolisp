# sinh cosh tanh

`(sinh number)` `(cosh number)` `(tanh number)`

The hyperbolic functions, each returning a float. `sinh` is the hyperbolic sine, `cosh` the hyperbolic cosine, and `tanh` the hyperbolic tangent. All three work on every backend: the interpreter and JVM use `Math.sinh`/`Math.cosh`/`Math.tanh`, while the WASM backend derives all three from its software `exp` approximation, so its results may differ slightly in the least significant digits (~1e-7 relative for `|x|` up to ~20, degrading for larger arguments like the software `exp` itself; `sinh` switches to a Taylor series below `|x| = 0.25`, so tiny arguments stay accurate). `(sinh 0)` is exactly `0.0` and `(cosh 0)` exactly `1.0` everywhere.

```lisp
(tanh 0) ; => 0.0
```
