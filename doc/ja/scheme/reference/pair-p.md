# pair?

`(pair? obj)`

`obj` がペアなら `#t`、それ以外なら `#f` を返します。空リストはペアではありません。

```scheme
(pair? '(a . b)) ; => #t
(pair? '()) ; => #f
(pair? 'a) ; => #f
```
