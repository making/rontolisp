# procedure?

`(procedure? obj)`

`obj` が手続き（組み込み手続き、`lambda`、`call/cc` が捕捉した継続）なら `#t` を、そうでなければ `#f` を返します。

```scheme
(procedure? car) ; => #t
(procedure? 'car) ; => #f
(procedure? (lambda (x) x)) ; => #t
```
