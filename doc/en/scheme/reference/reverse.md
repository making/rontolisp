# reverse

`(reverse list)`

Returns a newly allocated list of the elements of `list` in reverse order. Only the top level is reversed.

```scheme
(reverse '(1 2 3)) ; => (3 2 1)
(reverse '(a (b c) d)) ; => (d (b c) a)
```
