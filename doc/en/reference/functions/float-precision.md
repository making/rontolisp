# float-precision

`(float-precision float)`

Returns the number of significant radix-2 digits `float` holds, including the implicit leading bit: `53` for every normal double, fewer for a subnormal, `0` for a zero. An infinity or a NaN answers the representation's `53`, as [`float-digits`](float-digits.md) does for every float.

```lisp
(float-precision 1.5) ; => 53
```

```lisp
(list (float-precision 0.0) (float-precision 4.9406564584124654d-324)) ; => (0 1)
```
