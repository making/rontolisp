# get-setf-expansion

`(get-setf-expansion place &optional environment)`

Returns the five setf-expansion values for `place`: a list of temporary variables, the list of value forms they bind, a list holding one store variable, the writer form, and the reader form. Lite: a variable place expands to a `setq` writer with no temporaries; an accessor form `(f args...)` binds one temporary per argument and writes through `setf`. The `environment` argument is accepted and ignored (a macro's `&environment` parameter is bound to nil). Consume the values with `multiple-value-bind`, as in the portable `incf`-style macro idiom.

```lisp
(multiple-value-bind (vars vals stores writer reader)
    (get-setf-expansion 'x)
  (list vars vals (length stores) reader)) ; => (NIL NIL 1 X)
```
