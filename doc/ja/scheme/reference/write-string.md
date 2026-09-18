# write-string

`(write-string string [port [start [end]]])`

`string` の文字を（引用符なしで）現在の出力ポートに書き出します。`port` を渡すとそこへ書き出します。`start` と `end` は、添字 `start` から `end` の手前までの文字を選びます。

```scheme
(write-string "hello")
(newline)
```

```
hello
```

```scheme
(let ((p (open-output-string))) (write-string "hello" p 1 3) (get-output-string p)) ; => "el"
```
