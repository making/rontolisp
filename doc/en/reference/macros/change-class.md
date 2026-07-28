# change-class

`(change-class instance 'class-name initarg value ...)`

Changes the class of an existing instance **in place** and returns it: the object keeps its identity (every other reference sees the change), the slots the old and the new class share keep their values, the slots the new class adds are filled from their `:initform`s, and any supplied initargs are stored on top. The class name must be a literal quoted symbol naming a [`defclass`](../special-forms/defclass.md) class, like [`make-instance`](make-instance.md).

Out of scope: the MOP protocol around it (`update-instance-for-different-class` is never called), and changing between classes of unrelated inheritance chains keeps the slot values positionally instead of matching them by name.

```lisp
(defclass cc-connection () ((host :initarg :host :accessor cc-host)))
(defclass cc-pooled (cc-connection) ((kind :initarg :kind :accessor cc-kind :initform :none)))
(let* ((c (make-instance 'cc-connection :host "db"))
       (alias c))
  (change-class c 'cc-pooled :kind :shared)
  (list (type-of alias) (cc-host alias) (cc-kind alias))) ; => (CC-POOLED "db" :SHARED)
```
