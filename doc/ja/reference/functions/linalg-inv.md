# linalg:inv

`(linalg:inv matrix)`

正方行列の逆行列を返します。拡大行列 `[a | I]` に対するガウス・ジョルダンの消去法で計算されます。結果は packed double-float 配列です。linalg は速度優先で浮動小数点で計算するため、一般の逆行列には通常の丸めが生じます。連立一次方程式を解くには、通常は [`linalg:solve`](linalg-solve.md) を直接呼び出す方が明快です。

```lisp
(linalg:inv #2A((4 0) (2 4))) ; => #f((0.25 0.0) (-0.125 0.25))
```

特異行列（[`linalg:det`](linalg-det.md) が 0 の行列）には逆行列が存在せず、エラーを通知します。

```console
> (linalg:inv #2A((1 2) (2 4)))
Error: linalg: inv of a singular matrix
```
