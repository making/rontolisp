# bit-vector-p

`(bit-vector-p object)`

`object` がビットベクタなら真を返します。ビットベクタの値はまだ存在しません（`(make-array n :element-type 'bit)` は 0/1 を持つ素のベクタです）。そのため現在はすべての値に `nil` を返します。`(typep object 'bit-vector)` とまったく同じ答えを返すので、これを呼ぶ移植性の高いコードはロードでき、ビットベクタ表現が追加されても動き続けます。

```lisp
(bit-vector-p (vector 0 1)) ; => NIL
```

```lisp
(bit-vector-p (make-array 4 :element-type 'bit)) ; => NIL
```
