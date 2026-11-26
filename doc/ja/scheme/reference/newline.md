# newline

`(newline [port])`

現在の出力ポートに改行を書き出します。`port` を渡すとそこへ書き出します。`port` は開いているテキスト出力ポートでなければなりません。

```scheme
(display "one")
(newline)
(display "two")
(newline)
```

```
one
two
```

```scheme
(let ((p (open-output-string))) (display "a" p) (newline p) (get-output-string p)) ; => "a\n"
```
