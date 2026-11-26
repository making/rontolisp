# rontolisp:bits-bfloat16

`(rontolisp:bits-bfloat16 integer)`

Returns the float a `bfloat16` bit pattern encodes; only the low sixteen bits of the argument are read. Widening is exact and total, so this never rounds -- every pattern names a float, and `rontolisp:bfloat16-bits` takes it back unchanged.

```lisp
(list (rontolisp:bits-bfloat16 16256)
      (rontolisp:bits-bfloat16 (rontolisp:bfloat16-bits 0.1))) ; => (1.0 0.10009765625)
```

The second value is the point: `0.1` is not a bfloat16, and the round trip shows exactly which float the width can hold.
