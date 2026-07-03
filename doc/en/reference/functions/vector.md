# vector

`(vector &rest elements)`

Creates and returns a fresh rank-1 array containing the given elements, evaluated left to right; `(vector)` returns an empty vector `#()`. It is equivalent to [`make-array`](make-array.md) with the element count as the dimension followed by [`aref`](aref.md)-style stores, but in one step. Like `make-array` and `aref`, `vector` is not a first-class function value -- `#'vector` is unavailable, so call it directly.

```lisp
(vector 1 2 3) ; => #(1 2 3)
(vector) ; => #()
(aref (vector 10 20 30) 2) ; => 30
```
