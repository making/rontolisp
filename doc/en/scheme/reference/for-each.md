# for-each

`(for-each proc list1 list2 ...)`

Applies `proc` element-wise to the lists, from first to last, for its effect, like `map` without collecting results. With several lists it stops at the shortest. The value is unspecified.

```scheme
(for-each (lambda (x y) (display (+ x y)) (newline)) '(1 2) '(10 20))
```

```
11
22
```
