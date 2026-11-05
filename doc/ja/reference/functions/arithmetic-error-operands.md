# arithmetic-error-operands

`(arithmetic-error-operands condition)`

`arithmetic-error` の作成時に与えた `:operands` です。与えていなければ `nil` です。算術演算自身がシグナルした失敗は、[`arithmetic-error-operation`](arithmetic-error-operation.md) と同じく `nil` を持ちます。

```lisp
(handler-case (error 'division-by-zero :operation '/ :operands '(1 0))
  (arithmetic-error (e) (arithmetic-error-operands e))) ; => (1 0)
```
