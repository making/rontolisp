# eof-object?

`(eof-object? obj)`

`obj` がファイル終端オブジェクトなら `#t` を、そうでなければ `#f` を返します。

```scheme
(eof-object? (eof-object)) ; => #t
(eof-object? #f) ; => #f
```
