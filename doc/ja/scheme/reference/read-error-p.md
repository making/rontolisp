# read-error?

`(read-error? obj)`

`obj` が、不正な入力（予期しない `)`、閉じていないリストや文字列）に対して `read` が発生させるエラーなら `#t` を返します。それ以外のオブジェクトは `#f` です。

```scheme
(guard (e (#t (read-error? e))) (error "not a read error")) ; => #f
```
