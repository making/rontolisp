# return

`(return [value])`

Performs a non-local exit from the nearest enclosing loop (`do`, `dolist`, `dotimes`, or `loop`), causing that loop form to evaluate to `value` (or `nil` if omitted). The `value` is evaluated before the exit. Because it targets the innermost loop boundary, `return` is only valid inside such a loop. It may appear anywhere in the loop body, including in the middle of an expression, which abandons the enclosing expression.

```lisp
(dotimes (i 10) (when (= i 3) (return i))) ; => 3
```
