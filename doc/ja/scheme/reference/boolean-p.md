# boolean?

`(boolean? obj)`

`obj` が `#t` または `#f` なら `#t` を返します。空リストは真偽値ではありません。

```scheme
(boolean? #f) ; => #t
(boolean? '()) ; => #f
```
