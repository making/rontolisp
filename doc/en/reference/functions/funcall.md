# funcall

`(funcall function &rest args)`

Calls `function` with the given arguments and returns its result. The `function` argument may be a function value -- `#'name`, a `lambda`, or the result of `symbol-function` -- or a quoted symbol that names a function (`(funcall 'car x)`); the interpreter resolves the symbol at runtime, while the compilers rewrite a literal `(quote name)` in function position into `(function name)`.

```lisp
(funcall #'+ 3 4) ; => 7
```
