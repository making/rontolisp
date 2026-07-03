# linalg:dot

`(linalg:dot a b)`

numpy スタイルのドット積で、オペランドのランクに応じてディスパッチします。ベクタ . ベクタはスカラー（内積）を、行列 . ベクタとベクタ . 行列はベクタを、行列 . 行列は行列積を返します。スカラーのオペランドは [`linalg:mul`](linalg-mul.md) と同様に要素ごとに乗算されます。内側の次元が一致しない場合はエラーを通知します。行列積のみを意図している場合、[`linalg:matmul`](linalg-matmul.md) はさらにスカラーのオペランドを拒否します。

```lisp
(linalg:dot (linalg:from-list '(1 2 3)) (linalg:from-list '(4 5 6)))     ; => 32
(linalg:dot (linalg:from-list '((1 2) (3 4))) (linalg:from-list '(1 1))) ; => #(3 7)
(linalg:dot (linalg:from-list '(1 1)) (linalg:from-list '((1 2) (3 4)))) ; => #(4 6)
```
