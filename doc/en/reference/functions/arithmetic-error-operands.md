# arithmetic-error-operands

`(arithmetic-error-operands condition)`

The `:operands` an `arithmetic-error` was made with, `nil` when it was given none. A failure the arithmetic itself signals carries `nil`, as [`arithmetic-error-operation`](arithmetic-error-operation.md) does.

```lisp
(handler-case (error 'division-by-zero :operation '/ :operands '(1 0))
  (arithmetic-error (e) (arithmetic-error-operands e))) ; => (1 0)
```
