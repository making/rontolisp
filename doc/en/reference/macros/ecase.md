# ecase

`(ecase key (k1 body...) ((k2 k3) body...))`

The exhaustive variant of `case`: it dispatches on `key` with `eql` exactly like `case`, but has no default clause -- `t` and `otherwise` are treated as ordinary keys. If `key` matches no clause, `ecase` signals an `error` rather than returning nil, so it is used when every valid value should be covered explicitly.

```lisp
(let ((x 3)) (ecase x (1 'one) ((2 3) 'two-or-three))) ; => TWO-OR-THREE
```
