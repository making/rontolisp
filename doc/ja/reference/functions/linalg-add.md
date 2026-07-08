# linalg:add

`(linalg:add a b)`

`a` と `b` を要素ごとに加算し、新しい配列を返します。どちらの被演算子もスカラーにでき、その場合はもう一方の被演算子の shape にブロードキャストされます。両方が配列の場合は shape が一致していなければなりません (一致しない場合はエラーを通知します)。他の要素ごとの演算子は [`linalg:sub`](linalg-sub.md)、[`linalg:mul`](linalg-mul.md)、[`linalg:div`](linalg-div.md) です。

```lisp
(linalg:add #(1 2 3) 10)   ; => #d(11.0 12.0 13.0)
(linalg:add #(1 2) #(3 4)) ; => #d(4.0 6.0)
```
