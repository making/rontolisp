# slot-unbound

`(slot-unbound class instance slot-name)`

The generic function a read of an unbound slot calls -- through a `:reader` or `:accessor`, [`slot-value`](../macros/slot-value.md) or [`with-slots`](../macros/with-slots.md) -- with the instance's class, the instance and the slot name. What the method returns is the value of the read. The system method signals `unbound-slot`, so a read no method covers signals exactly as it would with no method at all.

A method specialized on `(eql 'slot)` fills a slot on first use:

```lisp
(defclass su-lazy () ((data :reader su-data :writer (setf su-data-store))))
(defmethod slot-unbound (class (o su-lazy) (slot (eql 'data)))
  (setf (su-data-store o) (list :computed)))
(su-data (make-instance 'su-lazy)) ; => (:COMPUTED)
```
