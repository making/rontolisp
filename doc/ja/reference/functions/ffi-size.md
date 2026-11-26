# ffi:size

`(ffi:size type)`

外部型のサイズをバイト数で返す。C の整数型名は LP64 の幅
(`:long` は 8)。`(:struct member...)` 指定子は C のパディング規則でレイアウトされる
ので、サイズには末尾パディングも含まれる。

```lisp
(list (ffi:size :int) (ffi:size :pointer) (ffi:size '(:struct :int :double)))
; => (4 8 16)
```

`(:struct :int :double)` が 12 ではなく 16 なのは、double が 8 バイト境界に揃えられ、構造体のサイズがそのアラインメントの倍数になるため。
