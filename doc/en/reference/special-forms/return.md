# return

`(return [value])`

Performs a non-local exit from the nearest enclosing loop (`do`, `dolist`, or `dotimes`), causing that loop form to evaluate to `value` (or `nil` if omitted). The `value` is evaluated before the exit. Because it targets the innermost loop boundary, `return` is only valid inside such a loop; it works where the surrounding operand stack is empty, such as a `when`/`if` branch or a loop-body statement.

```lisp
(dotimes (i 10) (when (= i 3) (return i))) ; => 3
```
