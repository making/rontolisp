# setq

`(setq name value ...)`

Assigns `value` to the variable `name`, evaluating `value` but not `name`. Multiple `name value` pairs may be given; they are assigned left to right, so a later `value` can read an earlier assignment. The value of the last assignment is returned. `setq` operates in the variable namespace only (Lisp-2).

```lisp
(let ((x 0)) (setq x 1 x (+ x 9)) x) ; => 10
```
