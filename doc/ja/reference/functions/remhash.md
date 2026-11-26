# remhash

`(remhash key table)`

`table` から `key` に対応するエントリを削除します。エントリが存在して削除された場合は `t` を、キーが見つからなかった場合は `nil` を返します。キーは `gethash` での検索と同じく構造的に照合されます。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (remhash 'a h)) ; => T
```
