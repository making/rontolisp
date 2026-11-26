# assv

`(assv obj alist)`

Like `assq`, but compares with `eqv?`, so numeric and character keys are found by value.

```scheme
(assv 5 '((2 3) (5 7) (11 13))) ; => (5 7)
```
