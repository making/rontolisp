# defvar

`(defvar name [value])`

Defines a global variable `name`, binding it to `value` only if `name` is not already bound; if it already has a value, `defvar` leaves it unchanged (it is idempotent). With no `value`, the variable is declared but left unbound. The `value` is evaluated only when a binding is actually established, and the name symbol is returned.

`defvar` also proclaims `name` **special**: a later [`let`](let.md)/`let*` of it establishes a dynamic binding (visible to functions called within the extent, restored on exit) rather than a lexical one. See [`let`](let.md) and [`progv`](progv.md).

```lisp
(defvar *counter* 0) ; => *COUNTER*
```

```lisp
(defvar *scale* 1)
(defun scaled (n) (* n *scale*))
(let ((*scale* 10)) (scaled 5)) ; => 50
```
