# apply

`(apply proc arg ... list)`

Calls `proc` with the elements of `list` as its arguments, preceded by any `arg`s given before the list.

```scheme
(apply + 1 2 '(3 4)) ; => 10
(apply max '(3 1 4)) ; => 4
```
