# unquote

`(unquote expression)` `,expression`

Inside a `quasiquote` template, evaluates `expression` and puts its value in place. Outside a template it is an error.

```scheme
`(1 ,(+ 1 1)) ; => (1 2)
(quasiquote (x (unquote (+ 1 2)))) ; => (x 3)
```
