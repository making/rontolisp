# values-list

`(values-list list)`

`list` を多値として展開します。先頭要素が主値になり、残りは多値コンシューマに渡ります。`(values-list '(1 2))` は `(values 1 2)` と等価です。空リストは値なし(nil)になります。

```lisp
(multiple-value-list (values-list '(1 2 3))) ; => (1 2 3)
```

```lisp
(multiple-value-bind (a b) (values-list '(10)) (list a b)) ; => (10 nil)
```
