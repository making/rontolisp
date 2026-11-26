# sin cos tan

`(sin radians)` `(cos radians)` `(tan radians)`

The three basic trigonometric functions, each taking an angle in radians and returning a float. `sin` is the sine, `cos` the cosine, and `tan` the tangent. Every backend computes them with fdlibm (`StrictMath` on the interpreter and the JVM, the same algorithm on WASM), its exact argument reduction for large arguments included, so the digits agree everywhere: `(sin 1e22)` is `-0.8522008497671888`. The zero and quadrant anchors are exact everywhere: `(sin 0)` is `0.0`, `(cos 0)` is `1.0`, `(sin (/ pi 2))` is `1.0`, `(cos pi)` is `-1.0`. `NaN` and infinite arguments give `NaN` on every backend.

```lisp
(cos 0) ; => 1.0
```
