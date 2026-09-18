# write-shared

`(write-shared obj)`

`write` と同様ですが、循環しているものだけでなく、`obj` の中に 2 回以上現れるすべてのペアとベクタをデータラベル付きで書き出します。ポート引数はありません。

```scheme
(define x (list 1 2))
(write-shared (list x x))
(newline)
```

```
(#0=(1 2) #0#)
```
