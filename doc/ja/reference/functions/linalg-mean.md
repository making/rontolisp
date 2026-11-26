# linalg:mean

`(linalg:mean array &key axis keepdims)`

すべての要素 (`:axis` なし)、または `:axis` に沿った算術平均を返します。`:axis` なしの値は [`linalg:sum`](linalg-sum.md) を [`linalg:size`](linalg-size.md) で割ったものです。numpy の還元と同様に、結果は要素の型に従います。packed double-float 配列 (linalg のコンストラクタが作るもの) は double を、素の整数配列は厳密な有理数を返します。キーワード引数 `:axis` / `:keepdims` は [`linalg:sum`](linalg-sum.md) と同じ規則です (負の `:axis` は末尾から数え、還元した軸は結果から除去 -- `:keepdims` で長さ 1 の軸として保持)。

```lisp
(linalg:mean #(1 2 3 4)) ; => 5/2
(linalg:mean #2A((1 2 3) (4 5 6)) :axis 0) ; => #d(2.5 3.5 4.5)
```
