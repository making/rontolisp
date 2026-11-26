# lambda

`(lambda formals body...)`

手続きを返します。`formals` は仮引数のリスト `(a b)`、全引数をリストで受け取る単一の変数 `args`、または最後の変数が残りの引数を受け取るドット対リスト `(a . rest)` のいずれかです。本体は内部定義で始められます。手続きは `#<procedure>` と表示されます。`#!optional` と `#!rest` には対応していません。

```scheme
((lambda (x y) (+ x y)) 3 4) ; => 7
((lambda args args) 1 2 3) ; => (1 2 3)
((lambda (a . rest) rest) 1 2 3) ; => (2 3)
```
