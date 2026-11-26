# scale-float

`(scale-float float integer)`

Returns `float × 2^integer` with exact IEEE 754 semantics (including the subnormal range). The first argument must be a float and the second an integer, as in SBCL: an integer, a ratio, a complex or a non-number first argument signals a `type-error` of type `FLOAT`, a non-integer second argument one of type `INTEGER`, the float refused first. An exponent no fixnum holds saturates the result.

```lisp
(scale-float 1.5 3) ; => 12.0
```
