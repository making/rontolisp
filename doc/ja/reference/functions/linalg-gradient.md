# linalg:gradient

`(linalg:gradient samples &optional spacing)`

サンプル値のベクタの数値微分を返します(numpy の `np.gradient` 相当)。内部の点は 2 次精度の中心差分、両端は 1 次精度の片側差分で計算するため、結果は入力と同じ長さになります([`linalg:diff`](linalg-diff.md) との違い)。`spacing` には、一様なサンプル間隔(数値、デフォルト 1)か、非一様なサンプルの座標ベクタ(入力と同じ長さ。numpy と同じ 2 次精度の内部公式で、2 次関数に対して厳密)を渡せます。ベクタ専用で、サンプルは 2 点以上必要です。結果は入力の幅を保持します。

```lisp
(linalg:gradient #(0 1 4 9 16)) ; => #d(1.0 2.0 4.0 6.0 7.0)
```

```lisp
(linalg:gradient #(0 1 4 9 16) 2) ; => #d(0.5 1.0 2.0 3.0 3.5)
```

```lisp
(linalg:gradient #(0 1 9) #(0 1 3)) ; => #d(1.0 2.0 4.0)
```
