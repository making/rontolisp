# case

`(case key (k1 body...) ((k2 k3) body...) (otherwise body...))`

`key` を一度だけ評価し、各節の評価されていないキーと `eql` で比較して、最初に一致した節の本体を実行し、その最後の値を返します。キーがリストである節は、`key` がそのリストのいずれかの要素と `eql` であれば一致します。最後の `t` または `otherwise` 節がデフォルトになります。何も一致せずデフォルトもない場合、`case` は nil を返します。

```lisp
(let ((x 2)) (case x (1 'one) ((2 3) 'two-or-three) (otherwise 'other))) ; => two-or-three
```
