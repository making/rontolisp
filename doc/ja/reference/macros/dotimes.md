# dotimes

`(dotimes (var count [result]) body...)`

`count` を一度だけ評価し、`var` を `0` から `count-1` までの連続する整数に束縛しながら本体を繰り返し実行します。ループが終了すると `var` は `count` に束縛され、省略可能な `result` 形式が評価されて返されます (省略時は nil)。内部のブロック境界で包まれたカウントループに展開されるため、本体内の `return` でループを脱出できます。

```lisp
(dotimes (i 3) (format t "~d~%" i))
```

```
0
1
2
```
