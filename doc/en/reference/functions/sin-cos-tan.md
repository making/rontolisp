# sin cos tan

`(sin radians)` `(cos radians)` `(tan radians)`

The three basic trigonometric functions, each taking an angle in radians and returning a float. `sin` is the sine, `cos` the cosine, and `tan` the tangent. The interpreter and JVM backends compute them with `Math.sin`/`Math.cos`/`Math.tan`; the WASM backend uses a software approximation (Cody-Waite argument reduction over quarter-turn quadrants plus Taylor polynomials), so its result may differ slightly in the least significant digits, and for very large arguments (beyond about `2^30`) it progressively loses precision where the JVM stays exact. The zero and quadrant anchors are exact everywhere: `(sin 0)` is `0.0`, `(cos 0)` is `1.0`, `(sin (/ pi 2))` is `1.0`, `(cos pi)` is `-1.0`. `NaN` and infinite arguments give `NaN` on every backend.

```lisp
(cos 0) ; => 1.0
```
