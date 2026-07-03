# linalg:matmul

`(linalg:matmul a b)`

`a` と `b` の行列積を返します（行列 . ベクタも可能です）。[`linalg:dot`](linalg-dot.md) と同様に振る舞いますが、いずれかのオペランドがスカラーの場合はエラーを通知します。これにより、要素ごとの [`linalg:mul`](linalg-mul.md) が暗黙に適用されてしまう場面で行列積を書いてしまう間違いを検出できます。内側の次元は一致しなければならず、不一致の場合はエラーを通知します。

```lisp
(linalg:matmul (linalg:from-list '((1 2) (3 4)))
               (linalg:from-list '((5 6) (7 8)))) ; => #2A((19 22) (43 50))
```
