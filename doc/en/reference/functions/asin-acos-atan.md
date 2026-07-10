# asin acos atan

`(asin number)` `(acos number)` `(atan number)`

The inverse trigonometric functions, each returning an angle in radians as a float. `asin` is the arcsine, `acos` the arccosine, and `atan` the arctangent. Only the one-argument `atan` is supported -- there is no two-argument `(atan y x)` form. All three work on every backend: the interpreter and JVM use `Math.asin`/`Math.acos`/`Math.atan`, while the WASM backend computes `atan` with a software approximation (argument folding plus a Taylor series, ~1e-15 relative error) and derives `asin`/`acos` from it, so a WASM result can differ from the interpreter's and the JVM's in the last digit or two. `(asin 1)` is exactly `pi/2`, `(acos 1)` exactly `0.0`, and an `asin`/`acos` argument outside `[-1, 1]` returns `NaN`.

```lisp
(atan 0) ; => 0.0
```
