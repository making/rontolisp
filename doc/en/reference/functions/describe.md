# describe

`(describe object &optional stream)`

Writes a description of `object` by calling [`describe-object`](describe-object.md) with the stream `stream` designates (`nil` is `*standard-output*`, `t` is `*terminal-io*`), and answers no values. `(describe 42)` writes `42` and `  [fixnum]` on two lines.

```lisp
(multiple-value-list (describe 42 (make-broadcast-stream))) ; => NIL
```
