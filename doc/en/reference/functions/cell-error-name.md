# cell-error-name

`(cell-error-name condition)`

The `name` slot of a `cell-error` condition -- the name of the cell that could not be accessed. Every `cell-error` subtype carries it, `unbound-variable`, `undefined-function` and [`unbound-slot`](../macros/slot-boundp.md) included.

```lisp
(defclass ce-box () ((v)))
(handler-case (slot-value (make-instance 'ce-box) 'v)
  (unbound-slot (e) (cell-error-name e))) ; => V
```

The `undefined-function` a call of an undefined name signals carries that name -- through `funcall`, `apply`, `symbol-function` or a direct call, on every backend:

```lisp
(handler-case (funcall (intern "CE-NO-SUCH-FUNCTION"))
  (undefined-function (e) (cell-error-name e))) ; => CE-NO-SUCH-FUNCTION
```

An undefined `(setf name)` function is named by the list `(setf name)`, as written, and the message spells it the same way:

```lisp
(handler-case (funcall #'(setf ce-no-such-setf) 1 2)
  (undefined-function (e) (cell-error-name e))) ; => (SETF CE-NO-SUCH-SETF)
```

So does the `unbound-variable` a read of an unbound name signals -- through [`symbol-value`](symbol-value.md), or a reference to a special variable declared without a value -- on every backend:

```lisp
(handler-case (symbol-value (intern "CE-NO-SUCH-VARIABLE"))
  (unbound-variable (e) (cell-error-name e))) ; => CE-NO-SUCH-VARIABLE
```

```lisp
(defvar *ce-unset*)
(handler-case *ce-unset*
  (unbound-variable (e) (cell-error-name e))) ; => *CE-UNSET*
```
