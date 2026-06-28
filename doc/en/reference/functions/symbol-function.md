# symbol-function

`(symbol-function symbol)`

Returns the function value bound to `symbol` in the function namespace -- the same value `#'name` denotes. The result can be passed to `funcall`/`apply` or stored. Because rontolisp is a Lisp-2, this looks only in the function namespace, never at a variable of the same name. In the compilers the argument must be a quoted symbol literal (`(symbol-function 'car)`), since the binding is resolved at compile time.

```lisp
(funcall (symbol-function 'car) '(1 2 3)) ; => 1
```
