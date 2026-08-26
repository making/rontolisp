# symbol-macrolet

`(symbol-macrolet ((name expansion)...) body...)`

Defines local, lexically scoped symbol macros for the body: each free reference to a `name` evaluates its `expansion` in its place, and a `setq`/`setf` of a `name` assigns through the expansion as a `setf` place. An inner binding of the same name (`let`/`let*`, a `lambda`/`defun` parameter, `do`, `dolist`, ...) shadows the symbol macro in its scope. Quoted data, function-namespace positions (`#'name`, call heads), `case`-family keys, `go` tags, and `block` names are never substituted, and declarations directly in the body are dropped. Sibling macros may reference each other; a self-referential expansion is substituted once, not expanded recursively. The global sibling is [`define-symbol-macro`](../special-forms/define-symbol-macro.md).

```lisp
(let ((cell (list 1 2)))
  (symbol-macrolet ((head (car cell)))
    (setf head 99)
    cell)) ; => (99 2)
```

```lisp
(symbol-macrolet ((x 42))
  (list (let ((x 1)) x) x)) ; => (1 42)
```
