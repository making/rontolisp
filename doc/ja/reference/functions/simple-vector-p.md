# simple-vector-p

`(simple-vector-p object)`

`object` が SIMPLE ベクタなら真を返します。SIMPLE ベクタとは、要素型 `t` の階数 1 配列で、フィルポインタなし、`:adjustable` でなく、displaced でないものです。[`vector`](vector.md) リテラルや素の [`make-array`](make-array.md) の結果はシンプルですが、文字列（要素型 `character`）、packed ベクタ、フィルポインタ付き・adjustable の配列、displaced ビューはそうではありません。`(typep object 'simple-vector)` とまったく同じ答えを返します。

```lisp
(simple-vector-p (vector 1 2)) ; => T
```

```lisp
(simple-vector-p "abc") ; => NIL
```

```lisp
(simple-vector-p (make-array 4 :fill-pointer 0)) ; => NIL
```
