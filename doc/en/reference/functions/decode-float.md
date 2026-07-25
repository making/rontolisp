# decode-float

`(decode-float float)`

Returns three values: the significand as a float in [1/2, 1), the binary exponent, and the sign (`1.0` or `-1.0`), such that `significand * 2^exponent * sign` is the original number. Zero decodes as `0.0`, `0` and its sign. The decomposition scales by two, which is exact in binary floating point, so every backend returns bit-identical values. [`scale-float`](scale-float.md) is the reverse step.

```lisp
(multiple-value-list (decode-float 6.5)) ; => (0.8125 3 1.0)
```
