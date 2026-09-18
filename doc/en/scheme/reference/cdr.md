# cdr

`(cdr pair)`

Returns the cdr of `pair`: for a list, the list without its first element.

Deviation: `(cdr '())` answers `()` instead of signalling an error, like `car`.

```scheme
(cdr '(1 2 3)) ; => (2 3)
(cdr '(1 . 2)) ; => 2
(cdr '((a) b c)) ; => (b c)
```
