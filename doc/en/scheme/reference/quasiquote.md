# quasiquote

`(quasiquote template)` `` `template ``

Builds a list or vector from `template`: parts marked with `unquote` (`,`) are evaluated, parts marked with `unquote-splicing` (`,@`) are evaluated and spliced in, and everything else is taken literally. Nesting is counted as R7RS specifies, so only the innermost level of a nested template is evaluated.

```scheme
`(1 ,(+ 1 1) 3) ; => (1 2 3)
`#(1 ,(* 2 3)) ; => #(1 6)
`(a . ,(+ 1 2)) ; => (a . 3)
```
