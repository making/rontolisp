# function

`(function name)` or `#'name`

Looks up `name` in the function namespace and returns the corresponding function as a first-class value; `#'name` is reader shorthand for `(function name)`. The argument is a name, not an evaluated expression. This is how a named function (or a `lambda`) is obtained so it can be passed to `funcall`/`apply` or a higher-order function like `mapcar` -- necessary in a Lisp-2 because a bare symbol refers to the variable namespace.

```lisp
(funcall (function +) 2 3) ; => 5
```
