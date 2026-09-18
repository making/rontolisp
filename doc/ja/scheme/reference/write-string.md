# write-string

`(write-string string)`

`string` の文字を（引用符なしで）現在の出力ポートに書き出します。ポート引数はなく、R7RS の省略可能な `start`/`end` 引数も受け付けません。

```scheme
(write-string "hello")
(newline)
```

```
hello
```
