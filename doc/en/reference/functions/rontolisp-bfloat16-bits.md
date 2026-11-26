# rontolisp:bfloat16-bits

`(rontolisp:bfloat16-bits real)`

Returns the `bfloat16` bit pattern of a real, an integer 0-65535. A bfloat16 is the top sixteen bits of an IEEE 754 single float: one sign bit, the same eight exponent bits an `f32` has, and seven mantissa bits. It therefore covers the whole `f32` range and trades precision for width, which is what makes it the storage format published machine-learning checkpoints use.

The narrowing rounds to **nearest, ties to even**. Truncating the low sixteen bits instead would answer the same value for most inputs and bias every sum downward, so the two exact midpoints below round in opposite directions.

`rontolisp:bits-bfloat16` takes the pattern back. Widening is exact and total -- every one of the 65536 patterns is a float, infinities and NaNs included -- so the pair is an involution on patterns, and the round trip through a value is the bfloat16-rounded value.

```lisp
(list (rontolisp:bfloat16-bits 1.0)
      (rontolisp:bfloat16-bits -2.5)
      (rontolisp:bfloat16-bits 1.00390625)
      (rontolisp:bfloat16-bits 1.01171875)) ; => (16256 49184 16256 16258)
```
