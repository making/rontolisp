# torch:matmul

`(torch:matmul a b)`

torch.matmul のランク規則に従う微分可能な行列積です。ベクトル同士は内積 (スカラーテンソル)、行列とベクトルは通常の積、どちらかがランク 3 以上ならバッチ積 (`linalg:matmul`: 末尾 2 軸が行列で先頭の軸はブロードキャスト) になります。勾配は両オペランドに流れ (行列の場合 `g . b^T` と `a^T . g`)、バッチ軸は他のブロードキャスト随伴と同様に合計で縮約されます。末尾 2 軸の `torch:transpose` ビューであるオペランドはその場で読まれます。`(torch:matmul q (torch:transpose k '(0 2 1)))` は forward でも backward でもコピーを作りません。

```lisp
(torch:data (torch:matmul (torch:tensor '((1.0 2.0) (3.0 4.0)))
                          (torch:tensor '((5.0 6.0) (7.0 8.0)))))
; => #f((19.0 22.0) (43.0 50.0))
(torch:item (torch:matmul (torch:tensor '(1.0 2.0)) (torch:tensor '(3.0 4.0)))) ; => 11.0
```
