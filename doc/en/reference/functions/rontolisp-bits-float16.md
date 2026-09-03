# rontolisp:bits-float16

`(rontolisp:bits-float16 integer)`

Returns the float an IEEE 754 binary16 (`f16`) bit pattern encodes; only the low sixteen bits of the argument are read. Every one of the 65536 patterns decodes to a float -- infinities and NaNs included -- but the round trip through `rontolisp:float16-bits` is not always the identity on a NaN's payload: the JDK's own `Float.float16ToFloat`/`Float.floatToFloat16` pair, which this primitive is built on, can quiet a signalling NaN on the way through. `rontolisp:bits-bfloat16`/`rontolisp:bfloat16-bits` has no such gap.

```lisp
(list (rontolisp:bits-float16 15360)
      (rontolisp:bits-float16 (rontolisp:float16-bits 0.1))) ; => (1.0 0.0999755859375)
```

The second value is the point: `0.1` is not an `f16` value, and the round trip
shows exactly which float the width can hold.
