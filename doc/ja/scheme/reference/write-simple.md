# write-simple

`(write-simple obj [port])`

`write` と同様ですが、共有構造を出現ごとに書き出します。循環のないデータでは `write` と同じです。循環は検出しません。`port` を渡すとそこへ書き出します。`port` は開いているテキスト出力ポートでなければなりません。

```scheme
(define x (list 1 2))
(write-simple (list x x))
(newline)
```

```
((1 2) (1 2))
```

```scheme
(let ((p (open-output-string)) (x (list 1 2))) (write-simple (list x x) p) (get-output-string p)) ; => "((1 2) (1 2))"
```
