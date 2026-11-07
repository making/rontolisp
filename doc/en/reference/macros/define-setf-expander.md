# define-setf-expander

`(define-setf-expander name lambda-list body...)`

Defines how `(setf (name args...) value)` expands. The body (a macro-style form builder, usually with backquote) is run at expansion time with the lambda list bound to the place's argument forms, and must return the five setf-expansion values with [`values`](../functions/values.md): the temporary variables, their value forms, the store variables, the store form, and the access form. An `&environment` parameter is accepted and bound to nil, and [`get-setf-expansion`](../functions/get-setf-expansion.md) is available to expand a sub-place. Works on every backend (the compilers run the expander at compile time). `setf` template symbols resolve in the defining package, like a [`defmacro`](../special-forms/defmacro.md). When `name` is also a macro, `setf` takes the expander, not the macro's expansion, and so does a `setf` of a [`symbol-macrolet`](symbol-macrolet.md) name whose expansion is such a call. [`incf`](incf.md), [`decf`](decf.md), [`push`](push.md), [`pushnew`](pushnew.md) and [`pop`](pop.md) read a place `name` makes through the access form, so the place needs no function of the same name; `rotatef`, `shiftf`, `psetf`, `remf` and a [`define-modify-macro`](define-modify-macro.md) still read it by calling `name`.

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
