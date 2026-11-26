# method-combination-error

`(method-combination-error format-control &rest args)`

Signals an error from a method combination's forms, for what its rules refuse of the methods as a whole: the message is formatted from `format-control` and `args`. See [`define-method-combination`](../macros/define-method-combination.md).

```lisp
(handler-case (method-combination-error "bad ~A" 2) (error () :refused)) ; => :REFUSED
```
