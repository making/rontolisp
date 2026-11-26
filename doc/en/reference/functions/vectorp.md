# vectorp

`(vectorp value)`

Returns `t` when `value` is a vector. Strings are vectors in Common Lisp, so they pass too. A vector is a **rank-1** array and nothing else, so an array of any other rank fails — [`arrayp`](arrayp.md) still answers `t` for it. The `vector` type specifier in `typep`/`typecase` checks the same rank.

```lisp
(vectorp (vector 1 2 3)) ; => T
```

```lisp
(list (vectorp "abc") (vectorp '(1 2))) ; => (T NIL)
```

```lisp
(list (vectorp #2A((1 2) (3 4))) (arrayp #2A((1 2) (3 4)))) ; => (NIL T)
```
