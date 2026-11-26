# ffi:peek

`(ffi:peek pointer type &optional offset)`

`pointer` から `offset` バイトの位置にある `type` の値を 1 つ読む。
`:string` はその位置の NUL 終端 UTF-8 を読み、`:pointer` は外部ポインタを返す。

```lisp
(let ((p (ffi:alloc 16)))
  (ffi:poke p :int 1)
  (ffi:poke p :int 2 8)
  (prog1 (list (ffi:peek p :int) (ffi:peek p :int 8)) (ffi:free p)))
; => (1 2)
```

オフセットは要素数ではなくバイト数。配列を辿るときは `(ffi:size type)` を自分で掛ける。
