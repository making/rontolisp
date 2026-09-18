# assq

`(assq obj alist)`

Returns the first pair of the association list `alist` whose car is `obj`, compared with `eq?`, or `#f` when there is none.

```scheme
(assq 'b '((a 1) (b 2))) ; => (b 2)
(assq 'c '((a 1) (b 2))) ; => #f
```
