# linalg:solve

`(linalg:solve a b)`

連立一次方程式 `a . x = b` を `x` について解きます。`a` は正方行列で、`b` がベクタならベクタの解を、行列なら（列の方程式系を 1 つずつ解いた）行列の解を返します。整数と有理数の入力に対しては厳密な有理数の結果を返します。実装は [`linalg:dot`](linalg-dot.md) を介して [`linalg:inv`](linalg-inv.md) を適用するため、特異な `a` はエラーを通知します。

```lisp
(linalg:solve #2A((2 1) (1 3)) #(3 5)) ; => #(4/5 7/5)
```
