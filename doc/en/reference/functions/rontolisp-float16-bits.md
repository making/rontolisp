# rontolisp:float16-bits

`(rontolisp:float16-bits real)`

Returns the IEEE 754 binary16 (`f16`) bit pattern of a real, an integer 0-65535. Unlike `bfloat16`, `f16` is a genuinely different exponent/mantissa split from `f32` -- five exponent bits and ten mantissa bits, not eight and seven -- so it trades range as well as precision for width, and a value outside its exponent range widens to an infinity rather than a rounded finite number.

The narrowing rounds to **nearest, ties to even**, same as `rontolisp:bfloat16-bits`: the two exact midpoints below round in opposite directions.

```lisp
(list (rontolisp:float16-bits 1.0)
      (rontolisp:float16-bits -2.5)
      (rontolisp:float16-bits 1.00048828125)
      (rontolisp:float16-bits 1.00146484375)) ; => (15360 49408 15360 15362)
```

`rontolisp:bits-float16` takes the pattern back. Sixteen bits fit a fixnum on
every backend, so -- unlike the wider IEEE pairs `float-features:single-float-bits`
and friends work with -- this pair needs no bignum model and is a `rontolisp:`
primitive rather than a `float-features:` one; see `rontolisp:widen-float-bits`
and `rontolisp:narrow-float-bits` for the bulk form a whole tensor of these
patterns actually arrives as.
