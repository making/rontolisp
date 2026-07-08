# linalg:sub

`(linalg:sub a b)`

`a` から `b` を要素ごとに減算し、新しい配列を返します。どちらの被演算子もスカラーにでき、その場合はもう一方の被演算子の shape にブロードキャストされます。両方が配列の場合は shape が一致していなければなりません (一致しない場合はエラーを通知します)。[`linalg:add`](linalg-add.md)、[`linalg:mul`](linalg-mul.md)、[`linalg:div`](linalg-div.md) も参照してください。

```lisp
(linalg:sub #(5 5) 1) ; => #f(4.0 4.0)
```
