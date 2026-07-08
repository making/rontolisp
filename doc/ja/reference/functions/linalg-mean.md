# linalg:mean

`(linalg:mean array)`

すべての要素の算術平均を返します。[`linalg:sum`](linalg-sum.md) を [`linalg:size`](linalg-size.md) で割った値です。numpy の還元と同様に、結果は要素の型に従います。packed double-float 配列 (linalg のコンストラクタが作るもの) は double を、素の整数配列は厳密な有理数を返します。

```lisp
(linalg:mean #(1 2 3 4)) ; => 5/2
```
