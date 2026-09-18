# display

`(display obj)`

`obj` を人が読むための形で現在の出力ポートに書き出します。文字列は引用符なしで、文字はその文字そのものとして書き、それ以外は `write` と同じ表示になります。循環するリストやベクタはデータラベル付きで書かれ（`#0=(a b . #0#)`）、循環のない共有構造は出現ごとに書き出されます。ポート引数はありません。

```scheme
(display "a \"quoted\" word")
(newline)
(display '(1 "two" #\3))
(newline)
```

```
a "quoted" word
(1 two 3)
```
