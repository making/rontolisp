# arrayp

`(arrayp object)`

Returns `t` when the object is an array and `nil` otherwise. A string **is** an array in Common Lisp (a rank-1 array of characters), so `(arrayp "abc")` is true; [`vectorp`](vectorp.md) answers the same for rank-1 arrays and strings.

```lisp
(list (arrayp (vector 1 2)) (arrayp "abc") (arrayp '(1 2))) ; => (T T NIL)
```
