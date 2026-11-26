# continue

`(continue [condition])`

最内のアクティブな `continue` リスタート — [`cerror`](../macros/cerror.md) が確立するもの — を起動します。アクティブなものがなければ `nil` を返します([`abort`](abort.md) と違いエラーにはなりません)。[`handler-bind`](../macros/handler-bind.md) ハンドラからこれを呼ぶと、中断していた `cerror` が `nil` を返して実行はその先へ再開します。

```lisp
(handler-bind ((error (lambda (c) (continue))))
  (list :after (cerror "Carry on." "recoverable problem"))) ; => (:AFTER NIL)
```
