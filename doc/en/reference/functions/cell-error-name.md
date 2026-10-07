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

So does the `unbound-variable` a read of an unbound name signals -- through [`symbol-value`](symbol-value.md), a reference to a special variable declared without a value, or a reference to a global before its first assignment ([`setq`](../special-forms/setq.md)) -- on every backend:

```lisp
(handler-case (symbol-value (intern "CE-NO-SUCH-VARIABLE"))
  (unbound-variable (e) (cell-error-name e))) ; => CE-NO-SUCH-VARIABLE
```

```lisp
(defvar *ce-unset*)
(handler-case *ce-unset*
  (unbound-variable (e) (cell-error-name e))) ; => *CE-UNSET*
```
