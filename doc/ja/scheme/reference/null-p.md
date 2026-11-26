# null?

`(null? obj)`

`obj` が空リストなら `#t`、それ以外なら `#f` を返します。

```scheme
(null? '()) ; => #t
(null? '(1)) ; => #f
(null? 'a) ; => #f
```
