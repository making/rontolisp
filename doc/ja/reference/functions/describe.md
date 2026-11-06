# describe

`(describe object &optional stream)`

`stream` が指すストリーム (`nil` は `*standard-output*`、`t` は `*terminal-io*`) を渡して [`describe-object`](describe-object.md) を呼び、`object` の説明を書きます。値は返しません。`(describe 42)` は `42` と `  [fixnum]` の 2 行を書きます。

```lisp
(multiple-value-list (describe 42 (make-broadcast-stream))) ; => NIL
```
