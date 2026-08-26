# ffi:address

`(ffi:address pointer-or-integer)`

外部ポインタと整数アドレスを、引数が求める向きに相互変換する --- つまり
自分自身の逆関数であり、`cffi:make-pointer`、`cffi:pointer-address`、
`cffi:null-pointer` はすべてこの 1 つの動詞から導かれる。アドレスは両方向とも符号なし
64 ビットとして扱われるので、2^63 以上のアドレスも負の値にならず往復する。

```lisp
(ffi:address (ffi:address 4096))
; => 4096
```

アドレス `0` はエラーではなく正当な NULL ポインタで、`(ffi:address 0)` が `cffi:null-pointer` の返す値そのもの。
