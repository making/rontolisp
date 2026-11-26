# map

`(map proc list1 list2 ...)`

Applies `proc` element-wise to the lists and returns the list of results, in order. With several lists, `proc` takes one argument per list and the result is as long as the shortest list.

```scheme
(map (lambda (x) (* x x)) '(1 2 3)) ; => (1 4 9)
(map + '(1 2 3) '(10 20)) ; => (11 22)
```
