# write-char

`(write-char char)`

文字 `char` を（`#\` 表記ではなく文字そのものとして）現在の出力ポートに書き出します。ポート引数はありません。

```scheme
(write-char #\a)
(write-char #\b)
(newline)
```

```
ab
```
