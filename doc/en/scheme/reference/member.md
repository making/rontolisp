# member

`(member obj list)` `(member obj list compare)`

Like `memq`, but compares with `equal?`, or with the procedure `compare` when one is given, called as `(compare obj element)`.

```scheme
(member (list 'a) '(b (a) c)) ; => ((a) c)
(member "b" '("a" "b" "c")) ; => ("b" "c")
(member 2.0 '(1 2 3) =) ; => (2 3)
```
