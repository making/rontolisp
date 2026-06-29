# hash-table-p

`(hash-table-p object)`

`object` がハッシュテーブル (`make-hash-table` で作られたもの) なら `t` を、それ以外なら `nil` を返します。ハッシュテーブルはコンスではないため、`consp` はそれに対して nil を返します。

```lisp
(hash-table-p (make-hash-table)) ; => t
```
