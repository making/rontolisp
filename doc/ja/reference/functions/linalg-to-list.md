# linalg:to-list

`(linalg:to-list array)`

linalg 配列をリストに戻します。ベクタはフラットなリストに、行列は行リストのリストになります。[`linalg:from-list`](linalg-from-list.md) の逆変換であり、配列の内容を `mapcar` や `reduce` などのリスト関数に渡す際に便利です。

```lisp
(linalg:to-list (linalg:from-list '((1 2) (3 4)))) ; => ((1.0 2.0) (3.0 4.0))
```
