# read-line

`(read-line)`

現在の入力ポートから現在の行の残りを読み、行末記号を除いた文字列として返します。文字が残っていなければファイル終端オブジェクトを返します。ポート引数はありません。

```stdin
first line
second line
```

```scheme
(write (read-line))
(newline)
(write (read-line))
(newline)
(write (eof-object? (read-line)))
(newline)
```

```
"first line"
"second line"
#t
```
