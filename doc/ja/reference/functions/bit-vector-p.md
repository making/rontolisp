# bit-vector-p

`(bit-vector-p object)`

`object` がビットベクタのとき真を返します。ビットベクタとは記憶要素型 `bit` で刻印されたランク1配列です（`#*` リテラル、または `:element-type 'bit` の [`make-array`](make-array.md) 結果）。`(typep object 'bit-vector)` と正確に一致します。ビットベクタは 0/1 を保持する汎用ベクタとして表示されます（どのバックエンドにもパックされたビット記憶はありません）。

```lisp
(bit-vector-p #*0110) ; => T
```

```lisp
(bit-vector-p (make-array 4 :element-type 'bit)) ; => T
```

```lisp
(bit-vector-p (vector 0 1)) ; => NIL
```
