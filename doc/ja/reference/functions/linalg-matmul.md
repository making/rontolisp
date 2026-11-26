# linalg:matmul

`(linalg:matmul a b)`

`a` と `b` の行列積です (numpy の `np.matmul`、`@` 演算子)。rank が 2 以下の場合は [`linalg:dot`](linalg-dot.md) と同様に振る舞いますが (行列 . ベクタも可能です)、いずれかのオペランドがスカラーの場合はエラーを通知します。これにより、要素ごとの [`linalg:mul`](linalg-mul.md) が暗黙に適用されてしまう場面で行列積を書いてしまう間違いを検出できます。内側の次元は一致しなければならず、不一致の場合はエラーを通知します。

いずれかの rank が 3 以上の場合は**スタックされた**行列積になります (torch の `bmm` / `matmul`)。最後の 2 軸が行列で、先頭側のすべての軸は numpy の規則でブロードキャストされます。したがって `(batch heads n d)` の query と `(batch heads d n)` の key の積は `(batch heads n n)` のアテンションスコアになります。rank 1 のオペランドは積のために昇格され (左側なら行、右側なら列)、結果からはその軸が再び落とされます。numpy とまったく同じ挙動です。

```lisp
(linalg:matmul #2A((1 2) (3 4))
               #2A((5 6) (7 8)))                          ; => #d((19.0 22.0) (43.0 50.0))
(linalg:shape (linalg:matmul (linalg:zeros '(2 3 4))
                             (linalg:zeros '(2 4 5))))    ; => (2 3 5)
(linalg:matmul (linalg:reshape (linalg:arange 8) '(2 2 2))
               #2A((1 0) (0 1)))                          ; => #d(((0.0 1.0) (2.0 3.0)) ((4.0 5.0) (6.0 7.0)))
```
