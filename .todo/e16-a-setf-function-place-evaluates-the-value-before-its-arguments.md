# e16. A setf-function place evaluates the new value before the place's arguments

Difficulty: Medium

```lisp
(defun (setf eo-u) (v x) (list v x))
(print (setf (eo-u (progn (princ "a") 1)) (progn (princ "b") 2)))
(defclass eo-c () ((s :accessor eo-s :initform 0)))
(let ((o (make-instance 'eo-c)))
  (setf (eo-s (progn (princ "c") o)) (progn (princ "d") 3)))
```

SBCL 2.2.9: `ab(2 1)`, then `cd` (CLHS 5.1.1.1: the place's subforms, left to right, then the
value). Interpreter, JVM, P1 and component: `ba(2 1)`, then `dc`. `expandSetf` lowers a
setf-function place -- a `(defun (setf name) ...)`, a `defclass` `:accessor`, a place no
definition makes -- to `(funcall #'%setf-name val arg...)`, which evaluates the value first.

## Plan

- Four-backend fixture first (a `defun (setf ...)` writer, a CLOS accessor, a late-bound place,
  `incf` / `push` through one).
- Bind the arguments ahead of the value only where the order is observable. A variable argument
  is not safe by itself (the value form may assign it), nor is a variable value (an argument may
  assign it): decide what proves a site unobservable (a constant value, constant arguments)
  before choosing. CLOS accessor writes sit in hot loops and in the size-pinned corpora.
