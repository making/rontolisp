# even?

`(even? n)`

Returns `#t` when the integer `n` is even. An inexact integer such as `2.0` is accepted; an argument that is not an integer signals an error.

```scheme
(even? 0) ; => #t
(even? 7) ; => #f
(even? 2.0) ; => #t
```
