# odd?

`(odd? n)`

Returns `#t` when the integer `n` is odd. An inexact integer such as `3.0` is accepted; an argument that is not an integer, such as `1.5`, signals an error.

```scheme
(odd? 3) ; => #t
(odd? 3.0) ; => #t
(odd? 4) ; => #f
```
