# symbol-function

`(symbol-function symbol)`

Returns the function value bound to `symbol` in the function namespace -- the same value `#'name` denotes. The result can be passed to `funcall`/`apply` or stored. Because rontolisp is a Lisp-2, this looks only in the function namespace, never at a variable of the same name. A quoted symbol literal (`(symbol-function 'car)`) resolves at compile time in the compilers; a runtime-computed symbol resolves late through the compiled name registry when the result is called -- with two deviations there: `functionp` of the result answers `nil`, and an undefined name signals at the call rather than at `symbol-function` itself.

```lisp
(funcall (symbol-function 'car) '(1 2 3)) ; => 1
```
