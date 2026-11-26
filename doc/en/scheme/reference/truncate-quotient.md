# truncate-quotient

`(truncate-quotient n1 n2)`

The quotient of `n1` by `n2`, truncated toward zero; inexact when either argument is. An argument that is not an integer signals an error.

```scheme
(truncate-quotient -7 2) ; => -3
(truncate-quotient 7 2) ; => 3
(truncate-quotient 7 2.0) ; => 3.0
```
