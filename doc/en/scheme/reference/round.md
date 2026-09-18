# round

`(round x)`

Returns the integer closest to `x`; a value exactly halfway rounds to the even integer. A flonum argument gives a flonum, an exact one an exact integer.

```scheme
(round 2.5) ; => 2.0
(round 3.5) ; => 4.0
(round 7/2) ; => 4
```
