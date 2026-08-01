# unexport

`(unexport symbols &optional package)`

The inverse of [`export`](export.md): makes `symbols` internal again in `package` (the current package by default). The symbols stay present — they are still reachable with the two-colon spelling — but are no longer visible unqualified through a [`use-package`](use-package.md). Returns `t`.

Consumed at compile time and subject to the same "before you define" rule as `export`; see that page for both.

```lisp
(defpackage #:partial (:use #:cl) (:export #:pub #:priv))
(in-package #:partial)
(unexport 'priv)
(defun pub () 1)
(defun priv () 2)
(in-package #:cl-user)
(+ (partial:pub) (partial::priv)) ; => 3
```
