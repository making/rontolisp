# or

`(or test...)`

Evaluates the tests left to right and answers the first value that is not `#f`, without evaluating the rest; answers `#f` when every test is false. `(or)` is `#f`.

```scheme
(or #f 2 3) ; => 2
(or (memq 'c '(a b c d)) 'none) ; => (c d)
(or #f #f) ; => #f
```
