# memq

`(memq obj list)`

Returns the first sublist of `list` whose car is `obj`, compared with `eq?`, or `#f` when there is none.

```scheme
(memq 'c '(a b c d)) ; => (c d)
(memq 'z '(a b c)) ; => #f
(memq (list 'a) '(b (a) c)) ; => #f
```
