# floor-quotient

`(floor-quotient n1 n2)`

The quotient of `n1` by `n2`, rounded toward negative infinity; inexact when either argument is. An argument that is not an integer signals an error.

```scheme
(floor-quotient -7 2) ; => -4
(floor-quotient 7 2) ; => 3
(floor-quotient -7.0 2) ; => -4.0
```
