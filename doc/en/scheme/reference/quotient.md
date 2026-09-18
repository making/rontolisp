# quotient

`(quotient n1 n2)`

Integer division truncated toward zero; the same as `truncate-quotient`. Dividing by exact zero signals an error.

```scheme
(quotient 17 5) ; => 3
(quotient -17 5) ; => -3
```
