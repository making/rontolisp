# copy-list

`(copy-list list)`

Returns a shallow copy of `list`: the top-level cons cells are freshly allocated, but the elements themselves are shared with the original. This lets you destructively modify the copy's structure without affecting the source. Nested sublists are not copied.

```lisp
(copy-list '(1 2 3)) ; => (1 2 3)
```
