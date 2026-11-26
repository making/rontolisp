# tailp

`(tailp object list)`

`object` が `list` の末尾のいずれかかどうかを返します。スパインの cons のいずれかと `eq`、または終端のアトムと `eql` であれば真です。したがって真リストに対する `(tailp nil list)` は真であり、ドットリストに対する `(tailp 'e '(a b . e))` も真です。

```lisp
(let ((l (list 1 2 3))) (tailp (cddr l) l)) ; => T
```

```lisp
(tailp 'e '(a b . e)) ; => T
```
