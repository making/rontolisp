# the

`(the type form)`

Evaluates `form` and returns its value unchanged. The type is not checked (there is no compiler type system to inform), so `the` is pure documentation -- it exists so annotated library sources load unchanged. Use `check-type` for an actual runtime type assertion.

```lisp
(the integer (+ 1 2)) ; => 3
```
