# read-char

`(read-char)`

現在の入力ポートから次の文字を読んで返します。入力が尽きていればファイル終端オブジェクトを返します。ポート引数はありません（サポートされるのは現在の入力ポート、つまり標準入力だけです）。

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
