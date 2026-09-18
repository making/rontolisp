# vector?

`(vector? obj)`

Returns `#t` if `obj` is a vector, otherwise `#f`. A string or a list is not a vector.

```scheme
(vector? #(1 2)) ; => #t
(vector? "abc") ; => #f
```
