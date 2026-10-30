# slot-unbound

`(slot-unbound class instance slot-name)`

未束縛のスロットを読んだとき(`:reader`・`:accessor`、[`slot-value`](../macros/slot-value.md)、[`with-slots`](../macros/with-slots.md) のいずれでも)、インスタンスのクラス、インスタンス、スロット名を引数に呼ばれる総称関数です。メソッドの返す値がその読み出しの値になります。システムメソッドは `unbound-slot` をシグナルするので、どのメソッドも扱わない読み出しはメソッドがない場合と同じくシグナルします。

`(eql 'slot)` に特定化したメソッドで、最初に使われたときにスロットを埋められます:

```lisp
(defclass su-lazy () ((data :reader su-data :writer (setf su-data-store))))
(defmethod slot-unbound (class (o su-lazy) (slot (eql 'data)))
  (setf (su-data-store o) (list :computed)))
(su-data (make-instance 'su-lazy)) ; => (:COMPUTED)
```
