# count

`(count item sequence)`

`sequence` の要素のうち `item` に `eql` なものの個数を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。比較は `eql` のみです。述語で数えるには `count-if` を使います。

```lisp
(count 2 '(1 2 3 2 2)) ; => 3
```

```lisp
(count #\a "banana") ; => 3
```
