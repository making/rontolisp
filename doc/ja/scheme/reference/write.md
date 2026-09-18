# write

`(write obj)`

`obj` を外部表現で現在の出力ポートに書き出します。文字列は引用符とエスケープ付きで、文字は `#\` 表記で書かれます。循環するリストやベクタはデータラベル付きで書かれ、循環のない共有構造は出現ごとに書き出されます。`'x` は `(quote x)` と、未規定値は `#!unspecific` と書かれます。ポート引数はありません。

```scheme
(write '(1 "two" #\3))
(newline)
(define c (list 'a 'b))
(set-cdr! (cdr c) c)
(write c)
(newline)
```

```
(1 "two" #\3)
#0=(a b . #0#)
```
