# read-char

`(read-char [port])`

現在の入力ポートから次の文字を読んで返します。入力が尽きていればファイル終端オブジェクトを返します。`port` を渡すとそのポートから読みます。`port` は開いているテキスト入力ポートでなければなりません。

```stdin
ab
```

```scheme
(write (read-char))
(write (read-char))
(newline)
```

```
#\a#\b
```

```scheme
(read-char (open-input-string "xy")) ; => #\x
```
