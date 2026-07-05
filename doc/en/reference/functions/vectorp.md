# vectorp

`(vectorp value)`

Returns `t` when `value` is a vector. Strings are vectors in Common Lisp, so they pass too. Like the `vector` type specifier in `typecase`, the rank is not checked — a multi-dimensional array also passes.

```lisp
(vectorp (vector 1 2 3)) ; => t
```

```lisp
(list (vectorp "abc") (vectorp '(1 2))) ; => (t nil)
```
