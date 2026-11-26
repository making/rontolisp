# setq

`(setq name value ...)`

Assigns `value` to the variable `name`, evaluating `value` but not `name`. Multiple `name value` pairs may be given; they are assigned left to right, so a later `value` can read an earlier assignment. The value of the last assignment is returned. `setq` operates in the variable namespace only (Lisp-2).

```lisp
(let ((x 0)) (setq x 1 x (+ x 9)) x) ; => 10
```

A `name` that no lexical binding holds and no `defvar` declares is the global variable of that name, wherever the `setq` stands -- inside a function body too -- on every backend, as in SBCL (which warns). Assigning an undeclared variable is undefined in Common Lisp; `defvar` or `defparameter` first is the portable spelling.

```lisp
(defun remember (v) (setq *last-seen* v))
(remember 42)
*last-seen* ; => 42
```

Such a global is unbound until its first assignment: a read of it before then -- by a function the program calls first, say -- signals an `unbound-variable` naming it, on every backend, as in SBCL.

```lisp
(defun total () (* *rate* 100))
(handler-case (total) (unbound-variable (e) (cell-error-name e))) ; => *RATE*
(setq *rate* 3)
(total) ; => 300
```
