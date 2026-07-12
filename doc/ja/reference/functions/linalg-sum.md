# linalg:sum

`(linalg:sum array &optional axis keepdims)`

`axis` なしではベクタまたは行列のすべての要素の合計を返します。numpy の還元と同様に、結果は要素の型に従います。packed double-float 配列 (linalg のコンストラクタが作るもの) は double を、素の整数配列は整数を返します。`keepdims` が非 nil の場合は、その値をすべての extent が 1 の形状の配列に包んで返します。整数の `axis` (負の値は numpy の規則で末尾から数えます) を渡すとその軸に沿って還元し、軸は結果から除去されます -- `keepdims` を渡すと長さ 1 の軸として保持されます。ベクタは `keepdims` なしではスカラーそのものに還元されます。平均には [`linalg:mean`](linalg-mean.md) を使ってください。

```lisp
(linalg:sum #2A((1 2) (3 4))) ; => 10
(linalg:sum #2A((1 2 3) (4 5 6)) 0) ; => #d(5.0 7.0 9.0)
(linalg:sum #2A((1 2 3) (4 5 6)) 1 t) ; => #d((6.0) (15.0))
```
