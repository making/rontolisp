# ffi:align

`(ffi:align type)`

外部型が要求するアラインメントをバイト数で返す。構造体のアラインメントは
メンバのうち最も大きいもので、[`ffi:size`](ffi-size.md) が適用するパディング規則の
土台になっている。

```lisp
(list (ffi:align :char) (ffi:align :double) (ffi:align '(:struct :char :double)))
; => (1 8 8)
```

値渡しの構造体は両方が正しくなければならないので、アラインメントとサイズは別々に問い合わせる。
