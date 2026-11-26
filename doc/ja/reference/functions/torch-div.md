# torch:div

`(torch:div a b)`

numpy スタイルのブロードキャストを伴う、微分可能な要素ごとの `a / b` (`linalg:div`) です。分子の勾配は `g / b`、分母の勾配は `-g * a / b^2` です。配列を素の数 (または追跡されないスカラーテンソル) で割ると **ビュー** が返ります。何かがデータを読むまで何も計算されず、その上の [`torch:softmax`](torch-softmax.md) は除算を自身のパスに畳み込みます。

```lisp
(torch:data (torch:div (torch:tensor '(6.0 9.0)) (torch:tensor '(2.0 3.0)))) ; => #f(3.0 3.0)
```
