# ceiling

`(ceiling x)`

Returns the smallest integer not less than `x`, a flonum for a flonum argument and exact for an exact one. An infinity or a NaN gives itself.

```scheme
(ceiling 2.1) ; => 3.0
(ceiling 7/2) ; => 4
```
