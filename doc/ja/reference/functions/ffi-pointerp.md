# ffi:pointerp

`(ffi:pointerp value)`

`value` が外部ポインタなら `t` を返す。ここではポインタは整数ではなく
独立した種類の値なので、`42` に対しては `nil` を返す --- ただしアドレスが期待される
場所では素の整数も受け付ける。

```lisp
(list (ffi:pointerp (ffi:address 4096)) (ffi:pointerp 42))
; => (T NIL)
```

`cffi:pointerp` はこの動詞そのもの。境界で誤った引数が来たときに、でたらめなアドレスではなく型エラーになるのはこのため。
