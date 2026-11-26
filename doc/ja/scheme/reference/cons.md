# cons

`(cons obj1 obj2)`

car が `obj1`、cdr が `obj2` の新しいペアを返します。cdr がリストならより長いリストになり、それ以外の cdr ならドット対になります。

```scheme
(cons 1 2) ; => (1 . 2)
(cons 'a '(b c)) ; => (a b c)
(cons '(a) '(b c)) ; => ((a) b c)
```
