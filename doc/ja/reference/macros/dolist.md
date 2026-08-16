# dolist

`(dolist (var list [result]) body...)`

`list` を一度だけ評価し、`var` を順次の各要素に束縛しながら本体を繰り返し実行します。リストを使い切ると `var` は nil に束縛され、省略可能な `result` 形式が評価されて返されます (省略時は nil)。本体は内部のブロック境界で包まれているため、その中の `return` でループを早期に脱出できます。

```lisp
(dolist (x '(a b c)) (format t "~a~%" x))
```

```
A
B
C
```
