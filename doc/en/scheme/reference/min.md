# min

`(min x1 x2 ...)`

Returns the smallest argument. If any argument is inexact, the result is inexact: `(min 1 2.0)` is `1.0`. Takes at least one argument.

```scheme
(min 3 1 2) ; => 1
(min 1 2.0) ; => 1.0
```
