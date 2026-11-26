# write-shared

`(write-shared obj [port])`

`write` と同様ですが、循環しているものだけでなく、`obj` の中に 2 回以上現れるすべてのペアとベクタをデータラベル付きで書き出します。`port` を渡すとそこへ書き出します。`port` は開いているテキスト出力ポートでなければなりません。

```scheme
(define x (list 1 2))
(write-shared (list x x))
(newline)
```

```
(#0=(1 2) #0#)
```

```scheme
(let ((p (open-output-string)) (x (list 1 2))) (write-shared (list x x) p) (get-output-string p)) ; => "(#0=(1 2) #0#)"
```
