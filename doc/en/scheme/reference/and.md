# and

`(and test...)`

Evaluates the tests left to right and stops at the first `#f`, answering `#f`; otherwise answers the value of the last test. `(and)` is `#t`.

```scheme
(and 1 2 'last) ; => last
(and 1 #f 3) ; => #f
(and) ; => #t
```
