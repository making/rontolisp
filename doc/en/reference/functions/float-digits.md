# float-digits

`(float-digits float)`

Returns the number of radix-2 digits in `float`'s significand, including the implicit leading bit: `53` for every normal double, fewer for a subnormal, `0` for a zero.

```lisp
(float-digits 1.5) ; => 53
```

```lisp
(float-digits 0.0) ; => 0
```
