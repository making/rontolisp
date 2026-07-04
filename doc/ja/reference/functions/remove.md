# remove

`(remove item sequence)`

`sequence` の要素のうち `item` に `eql` なものをすべて取り除いた新しいシーケンスを返します。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します。元のシーケンスは変更されません。破壊的な操作にはリスト専用の `delete` を使います。比較は `eql` のみです。

```lisp
(remove 2 '(1 2 3 2)) ; => (1 3)
```

```lisp
(remove #\l "hello") ; => "heo"
```
