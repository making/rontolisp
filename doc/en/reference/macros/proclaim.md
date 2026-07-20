# proclaim

`(proclaim declaration)`

A parsed no-op like `declaim`: the form evaluates to nil. This deviates from Common Lisp, where `proclaim` is a function whose argument is evaluated -- here it is classified as a macro, so the argument is not evaluated either (and `#'proclaim` is unsupported).

```lisp
(proclaim '(special *state*)) ; => NIL
```
