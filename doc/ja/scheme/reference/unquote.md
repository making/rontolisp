# unquote

`(unquote expression)` `,expression`

`quasiquote` テンプレートの中で `expression` を評価し、その値をその位置に置きます。テンプレートの外ではエラーです。

```scheme
`(1 ,(+ 1 1)) ; => (1 2)
(quasiquote (x (unquote (+ 1 2)))) ; => (x 3)
```
