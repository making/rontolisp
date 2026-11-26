# length

`(length list)`

Returns the number of elements of `list`.

Deviation: an improper list is not an error; its pairs are counted, so `(length '(1 . 2))` is `1`.

```scheme
(length '(1 2 3)) ; => 3
(length '((1 2) 3)) ; => 2
(length '()) ; => 0
```
