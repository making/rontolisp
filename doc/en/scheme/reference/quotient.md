# quotient

`(quotient n1 n2)`

Integer division truncated toward zero; the same as `truncate-quotient`. The quotient is inexact when either argument is; an argument that is not an integer, or dividing by exact zero, signals an error.

```scheme
(quotient 17 5) ; => 3
(quotient -17 5) ; => -3
(quotient 7.0 2) ; => 3.0
```
