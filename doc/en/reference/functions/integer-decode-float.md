# integer-decode-float

`(integer-decode-float float)`

Returns three values: the significand as an integer, the binary exponent, and the sign (`1.0` or `-1.0`), such that `significand * 2^exponent * sign` is the original number. Zero decodes as `0`, `0` and its sign. The decomposition scales by two, which is exact in binary floating point, so every backend returns bit-identical values. [`decode-float`](decode-float.md) is the same decomposition with a float significand.

```lisp
(multiple-value-list (integer-decode-float 6.5)) ; => (13 -1 1.0)
```
