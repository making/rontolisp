# list-ref

`(list-ref list k)`

Returns element `k` of `list`, counting from zero.

Deviation: a `k` past the end is not an error; the result is `()`.

```scheme
(list-ref '(a b c d) 2) ; => c
(list-ref '(a b c d) 0) ; => a
```
