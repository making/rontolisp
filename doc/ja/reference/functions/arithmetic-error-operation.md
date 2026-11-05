# arithmetic-error-operation

`(arithmetic-error-operation condition)`

`arithmetic-error` の作成時に与えた `:operation` です。与えていなければ `nil` です。算術演算自身がシグナルした失敗は `nil` を持ちます。何が失敗したかはメッセージが示します (SBCL は演算子を返します)。もう一方は [`arithmetic-error-operands`](arithmetic-error-operands.md) です。

```lisp
(handler-case (error 'division-by-zero :operation '/ :operands '(1 0))
  (arithmetic-error (e) (arithmetic-error-operation e))) ; => /
```
