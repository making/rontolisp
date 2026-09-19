# integer-decode-float

`(integer-decode-float float)`

Returns three values: the significand as an integer, the binary exponent, and the sign (`1` or `-1`, an integer -- a float sign is [`decode-float`](decode-float.md)'s business only), such that `significand * 2^exponent * sign` is the original number. The significand is scaled to exactly `(float-digits float)` bits -- 53 for a normal double, fewer for a subnormal. Zero decodes as `0`, `0` and its sign. The decomposition scales by two, which is exact in binary floating point, so every backend returns bit-identical values. [`decode-float`](decode-float.md) is the same decomposition with a float significand.

```lisp
(multiple-value-list (integer-decode-float 6.5)) ; => (7318349394477056 -50 1)
```
