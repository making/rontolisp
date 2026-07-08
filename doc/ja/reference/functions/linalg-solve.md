# linalg:solve

`(linalg:solve a b)`

連立一次方程式 `a . x = b` を `x` について解きます。`a` は正方行列で、`b` がベクタならベクタの解を、行列なら（列の方程式系を 1 つずつ解いた）行列の解を返します。実装は [`linalg:dot`](linalg-dot.md) を介して [`linalg:inv`](linalg-inv.md) を浮動小数点で適用するため、結果は packed double-float 配列であり、特異な `a` はエラーを通知します。

```lisp
(linalg:solve #2A((4 0) (2 4)) #(8 8)) ; => #f(2.0 1.0)
```
