# cons

`(cons obj1 obj2)`

Returns a newly allocated pair whose car is `obj1` and whose cdr is `obj2`. A pair whose cdr is a list is a longer list; any other cdr makes a dotted pair.

```scheme
(cons 1 2) ; => (1 . 2)
(cons 'a '(b c)) ; => (a b c)
(cons '(a) '(b c)) ; => ((a) b c)
```
