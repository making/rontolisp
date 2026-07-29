# print-object

`(print-object object stream)`

The generic function the printer consults. Defining a [`defmethod`](../special-forms/defmethod.md) on it for a [`defclass`](../special-forms/defclass.md) class or a [`defstruct`](../special-forms/defstruct.md) type makes `print`, `princ`, `prin1`, `princ-to-string`, `prin1-to-string` and [`format`](../macros/format.md)'s `~A`/`~S` render instances of that type through the method instead of the built-in `#S(...)` / `#<...>` syntax. The method writes to the stream it is given and its return value is ignored; [`print-unreadable-object`](../macros/print-unreadable-object.md) is the usual body.

There is no system method: a type no method specializes on keeps the built-in rendering, and a program that defines no `print-object` method prints exactly as it did before.

The one built-in rendering that is not `#S(...)`/`#<...>` is a CONDITION's: `princ`/`princ-to-string`/`~A` write its [`:report`](../macros/define-condition.md) instead. A `print-object` method on a condition class wins over that report, in both escape modes.

Lite: the method is consulted for the value the printing operator is given, not for one nested inside a printed list or vector — `(print (list obj))` still shows the built-in syntax for `obj`.

```lisp
(defstruct po-node value)
(defmethod print-object ((n po-node) stream)
  (print-unreadable-object (n stream :type t)
    (princ (po-node-value n) stream)))
(princ-to-string (make-po-node :value 42)) ; => "#<PO-NODE 42>"
```
