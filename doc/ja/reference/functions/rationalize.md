# rationalize

`(rationalize number)`

浮動小数点数を前後半 ulp の範囲で近似する最も単純な有理数を返すため、結果を float に戻すと入力が再現されます。整数と分数はすでに正確なのでそのまま返されます。

```lisp
(rationalize 0.1) ; => 1/10
```

```lisp
(rationalize 1.5) ; => 3/2
```
