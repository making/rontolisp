# scale-float

`(scale-float float integer)`

Returns `float × 2^integer` with exact IEEE 754 semantics (including the subnormal range).

```lisp
(scale-float 1.5 3) ; => 12.0
```
