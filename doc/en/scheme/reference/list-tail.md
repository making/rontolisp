# list-tail

`(list-tail list k)`

Returns the sublist of `list` obtained by omitting its first `k` elements. The result shares structure with `list`.

Deviation: a `k` past the end is not an error; the result is `()`.

```scheme
(list-tail '(a b c d) 2) ; => (c d)
(list-tail '(1 2 3) 0) ; => (1 2 3)
```
