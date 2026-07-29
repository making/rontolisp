# muffle-warning

`(muffle-warning [condition])`

リスタートシステムを使うプログラムで [`warn`](../macros/warn.md) が確立する `muffle-warning` リスタートを起動し、保留中の警告を**印字される前に**中止します — `warn` は静かに `nil` を返します。`warning` に対する [`handler-bind`](../macros/handler-bind.md) ハンドラから呼ぶことを想定しています。`muffle-warning` リスタートがアクティブでない場合(つまり `warn` の外)はエラーを通知します。

```lisp
(handler-bind ((warning (lambda (w) (muffle-warning))))
  (list :done (warn "nothing to see"))) ; => (:DONE NIL)
```
