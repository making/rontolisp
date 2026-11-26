# values-list

`(values-list list)`

Spreads `list` as multiple values: the first element is the primary value and the rest reach the multiple-value consumers, so `(values-list '(1 2))` is equivalent to `(values 1 2)`. An empty list yields no values (nil).

```lisp
(multiple-value-list (values-list '(1 2 3))) ; => (1 2 3)
```

```lisp
(multiple-value-bind (a b) (values-list '(10)) (list a b)) ; => (10 NIL)
```
