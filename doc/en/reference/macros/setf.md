# setf

`(setf place value [place2 value2 ...])`

Generalized assignment: stores `value` into the location named by `place` and returns the value. Beyond plain variables, the supported places are the list accessors `car`, `cdr`, `nth`, `first` through `fourth`, `rest`, and the `caXXXr` compositions, plus `elt` (a runtime list/array dispatch; a string allocated by [`make-string`](../functions/make-string.md) is written in place, a string literal is not), so you can mutate a specific slot of an existing structure in place. It expands into the appropriate primitive mutator (such as `rplaca`/`rplacd`). Place subforms are evaluated before the value, so the tail-collection idiom `(setf (cdr tail) (setf tail (list x)))` links the old tail.

```lisp
(let ((x (list 1 2 3))) (setf (second x) 99) x) ; => (1 99 3)
```

Multiple place/value pairs assign sequentially (each pair sees the effects of the previous ones), and the last value is returned:

```lisp
(let ((x (list 1 2 3))) (setf (car x) 9 (second x) 8) x) ; => (9 8 3)
```

Beyond the built-in places, a `defstruct` accessor, a CLOS `:accessor`, and a user-defined *setf-function* (`(defun (setf name) ...)`) are also places: `(setf (name arg...) value)` calls the writer with the new value first. See [defun](../special-forms/defun.md) for setf-function definitions.

```lisp
(defvar *mode* :xml)
(defun (setf my-mode) (m) (setq *mode* m))
(setf (my-mode) :html5)
*mode* ; => :HTML5
```
