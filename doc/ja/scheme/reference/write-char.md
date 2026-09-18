# write-char

`(write-char char [port])`

文字 `char` を（`#\` 表記ではなく文字そのものとして）現在の出力ポートに書き出します。`port` を渡すとそこへ書き出します。`port` は開いているテキスト出力ポートでなければなりません。

```scheme
(write-char #\a)
(write-char #\b)
(newline)
```

```
ab
```

```scheme
(let ((p (open-output-string))) (write-char #\a p) (get-output-string p)) ; => "a"
```
