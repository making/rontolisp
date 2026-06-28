# defvar

`(defvar name [value])`

Defines a global variable `name`, binding it to `value` only if `name` is not already bound; if it already has a value, `defvar` leaves it unchanged (it is idempotent). With no `value`, the variable is declared but left unbound. The `value` is evaluated only when a binding is actually established, and the name symbol is returned.

```lisp
(defvar *counter* 0) ; => *counter*
```

```lisp
(defvar *counter* 0)
*counter* ; => 0
```
