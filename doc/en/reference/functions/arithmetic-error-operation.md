# arithmetic-error-operation

`(arithmetic-error-operation condition)`

The `:operation` an `arithmetic-error` was made with, `nil` when it was given none. A failure the arithmetic itself signals carries `nil`: its message names what failed, where SBCL answers the operator. [`arithmetic-error-operands`](arithmetic-error-operands.md) is the other half.

```lisp
(handler-case (error 'division-by-zero :operation '/ :operands '(1 0))
  (arithmetic-error (e) (arithmetic-error-operation e))) ; => /
```
