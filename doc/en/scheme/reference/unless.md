# unless

`(unless test expression...)`

If `test` is `#f`, evaluates the expressions and answers the last value; otherwise the value is unspecified. At least one expression is required.

```scheme
(unless (> 1 2) 'ran) ; => ran
(list (unless #t 'ran)) ; => (#!unspecific)
```
