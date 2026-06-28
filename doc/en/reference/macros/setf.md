# setf

`(setf place value)`

Generalized assignment: stores `value` into the location named by `place` and returns the value. Beyond plain variables, the supported places are the list accessors `car`, `cdr`, `nth`, `first` through `fourth`, `rest`, and the `caXXXr` compositions, so you can mutate a specific slot of an existing structure in place. It expands into the appropriate primitive mutator (such as `rplaca`/`rplacd`).

```lisp
(let ((x (list 1 2 3))) (setf (second x) 99) x) ; => (1 99 3)
```
