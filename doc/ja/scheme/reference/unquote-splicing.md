# unquote-splicing

`(unquote-splicing expression)` `,@expression`

`quasiquote` テンプレートの中で `expression`（リストを返す必要があります）を評価し、その要素をその位置に展開します。テンプレートの外ではエラーです。

```scheme
`(1 ,@(list 2 3) 4) ; => (1 2 3 4)
`(x ,@'() y) ; => (x y)
```
