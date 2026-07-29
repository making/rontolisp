# find-restart

`(find-restart identifier [condition])`

`identifier`(シンボルまたはキーワード)という名前の最内のアクティブなリスタートを第一級のリスタートオブジェクトとして返します。該当がなければ `nil` を返します。リスタートオブジェクトを渡した場合はそのまま返ります。返ったオブジェクトは [`invoke-restart`](invoke-restart.md) に渡したり [`restart-name`](restart-name.md) で読んだりできます。lite: 省略可能な `condition` 引数は受理された上で無視されます(リスタートとコンディションの関連付けはありません)。

```lisp
(restart-case
    (let ((r (find-restart 'retry)))
      (list (null r) (restart-name r)))
  (retry () nil)) ; => (NIL RETRY)
```
