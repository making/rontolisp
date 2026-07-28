# cell-error-name

`(cell-error-name condition)`

`cell-error` コンディションの `name` スロット — アクセスできなかったセルの名前です。`unbound-variable`、`undefined-function`、[`unbound-slot`](../macros/slot-boundp.md) を含むすべての `cell-error` のサブタイプが持っています。

```lisp
(defclass ce-box () ((v)))
(handler-case (slot-value (make-instance 'ce-box) 'v)
  (unbound-slot (e) (cell-error-name e))) ; => V
```
