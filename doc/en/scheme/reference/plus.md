# +

`(+ z ...)`

Returns the sum of its arguments; `(+)` is `0`. Exact arguments give an exact result (integers and ratios); any inexact argument makes the result inexact. Complex arguments take part in the arithmetic.

```scheme
(+ 1 2 3) ; => 6
(+ 1/2 1/3) ; => 5/6
(+ 1 2.5) ; => 3.5
```
