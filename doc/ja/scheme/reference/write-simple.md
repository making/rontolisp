# write-simple

`(write-simple obj)`

`write` と同様ですが、共有構造を出現ごとに書き出します。循環のないデータでは `write` と同じです。循環は検出しません。ポート引数はありません。

```scheme
(define x (list 1 2))
(write-simple (list x x))
(newline)
```

```
((1 2) (1 2))
```
