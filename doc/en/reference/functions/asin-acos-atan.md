# asin acos atan

`(asin number)` `(acos number)` `(atan number)`

The inverse trigonometric functions, each returning an angle in radians as a float. `asin` is the arcsine, `acos` the arccosine, and `atan` the arctangent. Only the one-argument `atan` is supported -- there is no two-argument `(atan y x)` form. Available on the interpreter and JVM backends only (not WASM).

```lisp
(atan 0) ; => 0.0
```
