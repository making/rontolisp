# linalg:reshape

`(linalg:reshape array shape)`

指定した shape を持ち、同じ要素を行優先の順序で並べた新しい配列を返します。`shape` はベクタの場合は整数、行列の場合はリスト `(rows cols)` で、その総サイズは入力の [`linalg:size`](linalg-size.md) と一致しなければなりません -- 一致しない場合はエラーを通知します。shape のうち 1 つの extent は `-1` にでき、要素数から推論されます (numpy と同じ)。裸の `-1` は flatten と同じで、`-1` が 2 つ以上あるとエラーを通知します。ベクタへの変形という一般的な特殊ケースが [`linalg:flatten`](linalg-flatten.md) です。

```lisp
(linalg:reshape (linalg:arange 6) '(2 3)) ; => #d((0.0 1.0 2.0) (3.0 4.0 5.0))
(linalg:shape (linalg:reshape (linalg:arange 12) '(3 -1))) ; => (3 4)
```
