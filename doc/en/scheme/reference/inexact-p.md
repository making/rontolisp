# inexact?

`(inexact? z)`

Returns `#t` when the number `z` is inexact: a flonum, or a complex either of whose parts is one.

```scheme
(inexact? 1.0) ; => #t
(inexact? 1) ; => #f
```
