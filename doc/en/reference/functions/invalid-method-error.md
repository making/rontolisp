# invalid-method-error

`(invalid-method-error method format-control &rest args)`

Signals an error from a method combination's forms, for a method its groups or its rules refuse: the message names the method and is formatted from `format-control` and `args`. See [`define-method-combination`](../macros/define-method-combination.md).

```lisp
(handler-case (invalid-method-error 1 "bad ~A" 2) (error () :refused)) ; => :REFUSED
```
