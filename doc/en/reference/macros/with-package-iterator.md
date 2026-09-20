# with-package-iterator

`(with-package-iterator (name package-list symbol-type...) body...)`

Binds `name` to a LOCAL FUNCTION (an `flet`, not CL's `macrolet`) that walks the symbols of the packages in `package-list` (a designator or a list of them) whose accessibility is one of the `symbol-type`s (`:internal`, `:external`, `:inherited`; at least one, or a `program-error`): each call answers four values -- `t`, the symbol, its status and its package -- and `nil` once the walk is over. The package-list form is evaluated once, and the walk is the same set [`do-symbols`](do-symbols.md) visits, so every symbol it hands out is what [`find-symbol`](../functions/find-symbol.md) answers for its name in that package.

```lisp
(make-package :wpi-demo :use nil)
(intern "A" :wpi-demo)
(intern "B" :wpi-demo)
(let ((names nil))
  (with-package-iterator (next :wpi-demo :internal)
    (loop (multiple-value-bind (more sym status) (next)
            (unless more (return))
            (push (list (symbol-name sym) status) names))))
  (sort names #'string< :key #'car)) ; => (("A" :INTERNAL) ("B" :INTERNAL))
```
