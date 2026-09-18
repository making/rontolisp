# exact?

`(exact? z)`

Returns `#t` when the number `z` is exact: an integer or a ratio. Flonums are inexact.

```scheme
(exact? 1/2) ; => #t
(exact? 1.0) ; => #f
```
