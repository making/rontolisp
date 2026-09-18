# zero?

`(zero? z)`

Returns `#t` when `z` is zero, exact or inexact (`-0.0` included).

```scheme
(zero? 0) ; => #t
(zero? -0.0) ; => #t
(zero? 1/2) ; => #f
```
