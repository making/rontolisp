# with-package-iterator

`(with-package-iterator (name package-list symbol-type...) body...)`

Lite expansion: binds `name` to a LOCAL FUNCTION (an `flet`, not CL's `macrolet`) that always reports no more symbols -- there is no intern table to iterate, so an iteration loop over it runs zero times (cl-ppcre's `regex-apropos`). The package-list form is evaluated once, for effect.

```lisp
(with-package-iterator (next nil :external)
  (multiple-value-bind (morep sym) (next)
    (list morep sym))) ; => (NIL NIL)
```
