# when

`(when test expression...)`

If `test` is true, evaluates the expressions and answers the last value; otherwise the value is unspecified. At least one expression is required.

```scheme
(when (> 2 1) 'a 'b) ; => b
(list (when (> 1 2) 'a)) ; => (#!unspecific)
```
