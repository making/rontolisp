# promise?

`(promise? obj)`

`obj` が `delay`、`delay-force`、`make-promise`、`cons-stream` で作られたプロミスなら `#t`、そうでなければ `#f` を返します。プロミスは手続きではありません。

```scheme
(promise? (delay 1)) ; => #t
(promise? 5) ; => #f
(procedure? (delay 1)) ; => #f
```
