# integer?

`(integer? obj)`

Returns `#t` when `obj` is an integer, exact or inexact: `(integer? 2.0)` is `#t`. Use `exact-integer?` to exclude flonums.

```scheme
(integer? 2.0) ; => #t
(integer? 2.5) ; => #f
(integer? 4/2) ; => #t
```
