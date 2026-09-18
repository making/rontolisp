# list-copy

`(list-copy obj)`

リスト `obj` の新しいコピーを返します。コピーされるのは骨格だけで要素は共有されるため、コピーを `set-car!` で書き換えても元のリストは変わりません。

```scheme
(list-copy '(1 2 3)) ; => (1 2 3)
(define lst (list 1 2 3))
(define cp (list-copy lst))
(set-car! cp 99)
lst ; => (1 2 3)
```
