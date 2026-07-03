# linalg:reshape

`(linalg:reshape array shape)`

指定した shape を持ち、同じ要素を行優先の順序で並べた新しい配列を返します。`shape` はベクタの場合は整数、行列の場合はリスト `(rows cols)` で、その総サイズは入力の [`linalg:size`](linalg-size.md) と一致しなければなりません -- 一致しない場合はエラーを通知します。ベクタへの変形という一般的な特殊ケースが [`linalg:flatten`](linalg-flatten.md) です。

```lisp
(linalg:reshape (linalg:arange 6) '(2 3)) ; => #2A((0 1 2) (3 4 5))
```
