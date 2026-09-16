# simple-bit-vector-p

`(simple-bit-vector-p object)`

`object` が SIMPLE ビットベクタのとき真を返します。SIMPLE ビットベクタとは、フィルポインタがなく、adjustable でも displaced でもない [`bit-vector-p`](bit-vector-p.md) です。`(typep object 'simple-bit-vector)` と正確に一致します。

```lisp
(simple-bit-vector-p #*0110) ; => T
```

```lisp
(simple-bit-vector-p (make-array 4 :element-type 'bit :fill-pointer 0)) ; => NIL
```

```lisp
(simple-bit-vector-p 0) ; => NIL
```
