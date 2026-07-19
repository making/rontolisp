# defsetf

`(defsetf access-fn update-fn)`

Registers a `setf` expansion for `access-fn`. The **short form** above makes `(setf (access-fn args...) value)` expand to `(update-fn args... value)`. The **long form** `(defsetf access-fn (lambda-list) (store-var...) body...)` evaluates its body at expansion time -- the lambda list bound to temporaries for the argument forms and the store variables bound to the new-value temporaries -- and must return the store form. For the full five-value protocol use [`define-setf-expander`](define-setf-expander.md). Works on every backend.

```lisp
(defun ref-value (box) (car box))
(defsetf ref-value rplaca)
(let ((box (list 1)))
  (setf (ref-value box) 42)
  box) ; => (42)
```
