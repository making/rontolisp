# linalg:sum

`(linalg:sum array)`

ベクタまたは行列のすべての要素の合計を返します。numpy の還元と同様に、結果は要素の型に従います。packed double-float 配列 (linalg のコンストラクタが作るもの) は double を、素の整数配列は整数を返します。平均には [`linalg:mean`](linalg-mean.md) を使ってください。

```lisp
(linalg:sum #2A((1 2) (3 4))) ; => 10
```
