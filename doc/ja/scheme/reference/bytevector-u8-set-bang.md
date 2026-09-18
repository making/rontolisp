# bytevector-u8-set!

`(bytevector-u8-set! bytevector k byte)`

`bytevector` の `k` 番目の要素に `byte` を格納し、未規定値を返します。0〜255 の外の `byte` は切り詰めずにエラーにします（`bytevector-u8-set!: not a byte: -1`）。リテラル `#u8(...)` は評価のたびに新しいバイトベクタになるので、それに格納してもプログラムは変わりません。

```scheme
(let ((b (bytevector 1 2 3))) (bytevector-u8-set! b 0 255) b) ; => #u8(255 2 3)
```
