# linalg:inv

`(linalg:inv matrix)`

正方行列の逆行列を返します。拡大行列 `[a | I]` に対するガウス・ジョルダンの消去法で計算されます。整数と有理数の入力に対しては厳密な有理数の結果を返します。連立一次方程式を解くには、通常は [`linalg:solve`](linalg-solve.md) を直接呼び出す方が明快です。

```lisp
(linalg:inv #2A((1 2) (3 4))) ; => #2A((-2 1) (3/2 -1/2))
```

特異行列（[`linalg:det`](linalg-det.md) が 0 の行列）には逆行列が存在せず、エラーを通知します。

```console
> (linalg:inv #2A((1 2) (2 4)))
Error: linalg: inv of a singular matrix
```
