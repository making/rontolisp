# unbound-slot-instance

`(unbound-slot-instance condition)`

`unbound-slot` コンディションがシグナルされたとき、スロットが未束縛だったオブジェクトです — スロット名を返す [`cell-error-name`](cell-error-name.md) と対になります。[`slot-boundp`](../macros/slot-boundp.md) も参照してください。

```lisp
(defclass usi-box () ((v)))
(handler-case (slot-value (make-instance 'usi-box) 'v)
  (unbound-slot (e) (type-of (unbound-slot-instance e)))) ; => USI-BOX
```
