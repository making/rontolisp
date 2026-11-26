# load-time-value

`(load-time-value form [read-only-p])`

Evaluates `form` once per occurrence in the source, not once per use: the result is computed the first time that occurrence is reached and reused from then on. `read-only-p` is accepted and ignored.

The compiled backends hoist the result into a generated global filled on first use; the interpreter memoizes the occurrence. Filling is lazy rather than at program start, so an occurrence never reached is never evaluated -- which matters because a value form spliced out of a library routinely needs globals that later top-level forms initialize.

A value form cheap enough not to be worth a slot -- an atom, a variable read, or a `quote`/`function`/`find-package` wrapper -- keeps the plain lowering and is simply re-evaluated. The `--no-gc` backend never hoists.

```lisp
(load-time-value (+ 1 2)) ; => 3
```

```lisp
(defvar *n* 0)
(defun bump () (setq *n* (+ *n* 1)) *n*)
(defun probe () (load-time-value (bump)))
(list (probe) (probe) (probe)) ; => (1 1 1)
```
