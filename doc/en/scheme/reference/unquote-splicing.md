# unquote-splicing

`(unquote-splicing expression)` `,@expression`

Inside a `quasiquote` template, evaluates `expression`, which must answer a list, and splices its elements in place. Outside a template it is an error.

```scheme
`(1 ,@(list 2 3) 4) ; => (1 2 3 4)
`(x ,@'() y) ; => (x y)
```
