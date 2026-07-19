# define-setf-expander

`(define-setf-expander name lambda-list body...)`

Defines how `(setf (name args...) value)` expands. The body (a macro-style form builder, usually with backquote) is run at expansion time with the lambda list bound to the place's argument forms, and must return the five setf-expansion values with [`values`](../functions/values.md): the temporary variables, their value forms, the store variables, the store form, and the access form. An `&environment` parameter is accepted and bound to nil, and [`get-setf-expansion`](../functions/get-setf-expansion.md) is available to expand a sub-place. Works on every backend (the compilers run the expander at compile time). `setf` template symbols resolve in the defining package, like a [`defmacro`](../special-forms/defmacro.md).

```lisp
(defun my-first (x) (first x))
(define-setf-expander my-first (place)
  (let ((store (gensym)))
    (values '() '() (list store)
            `(progn (rplaca ,place ,store) ,store)
            `(my-first ,place))))
(let ((lst (list 1 2 3)))
  (setf (my-first lst) 99)
  lst) ; => (99 2 3)
```
