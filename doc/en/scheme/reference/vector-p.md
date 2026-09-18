# vector?

`(vector? obj)`

Returns `#t` if `obj` is a vector, otherwise `#f`. A string, a list or a bytevector is not a vector.

```scheme
(vector? #(1 2)) ; => #t
(vector? "abc") ; => #f
```
