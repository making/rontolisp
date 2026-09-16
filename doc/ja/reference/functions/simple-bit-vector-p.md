# simple-bit-vector-p

`(simple-bit-vector-p object)`

`object` が SIMPLE ビットベクタなら真を返します。[`bit-vector-p`](bit-vector-p.md) と同様、現在はすべての値に `nil` を返します。ビットベクタの値はまだ存在せず、単純さは表現と一緒に決まるためです。`(typep object 'simple-bit-vector)` とまったく同じ答えを返します。

```lisp
(simple-bit-vector-p (vector 0 1)) ; => NIL
```

```lisp
(simple-bit-vector-p 0) ; => NIL
```
