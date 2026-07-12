# linalg:sub

`(linalg:sub a b)`

`a` から `b` を要素ごとに減算し、新しい配列を返します。被演算子は [`linalg:add`](linalg-add.md) に記載のとおり numpy の規則でブロードキャストされます: スカラーはもう一方の被演算子の shape にブロードキャストされ、shape の異なる 2 つの配列は末尾の軸から揃えて、揃えた各軸の長さが等しいかどちらかが 1 のときにブロードキャストされます。[`linalg:mul`](linalg-mul.md)、[`linalg:div`](linalg-div.md) も参照してください。

```lisp
(linalg:sub #(5 5) 1) ; => #d(4.0 4.0)
```
