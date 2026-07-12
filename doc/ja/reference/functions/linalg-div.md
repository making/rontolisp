# linalg:div

`(linalg:div a b)`

`a` を `b` で要素ごとに除算し、新しい packed float 配列を返します。被演算子は [`linalg:add`](linalg-add.md) に記載のとおり numpy の規則でブロードキャストされます: スカラーはもう一方の被演算子の shape にブロードキャストされ、shape の異なる 2 つの配列は末尾の軸から揃えて、揃えた各軸の長さが等しいかどちらかが 1 のときにブロードキャストされます。

```lisp
(linalg:div #(1 2 3) 2) ; => #d(0.5 1.0 1.5)
```
