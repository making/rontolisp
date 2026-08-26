# ffi:free

`(ffi:free pointer)`

[`ffi:alloc`](ffi-alloc.md) で得たメモリを解放し、`nil` を返す。外部メモリを
回収する仕組みは無いので、解放しなければプロセスが終わるまで漏れたままになる。
二重解放は C と同じく未定義動作。

```lisp
(let ((p (ffi:alloc 4)))
  (ffi:poke p :int 7)
  (ffi:free p))
; => NIL
```

解放後もポインタ値そのものは有効な値のままだが、指す先を読んではいけない。
