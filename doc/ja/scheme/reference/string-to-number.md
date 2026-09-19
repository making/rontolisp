# string->number

`(string->number string)` `(string->number string radix)`

`string` を基数 `radix`（既定値 10）の数として解析して返します。数として読めなければ `#f` を返します。符号と指数を任意に伴う整数、分数、小数と、大文字小文字を問わず `+inf.0` `-inf.0` `+nan.0` `-nan.0` を読めます。`string` 自体が R7RS の接頭辞——基数（`#x`、`#b`、`#o`、`#d`）と正確性（`#e`、`#i`）をそれぞれ高々一つ、どちらの順でも——で始まっていてもよく、その場合は `radix` より優先されます。小数に対する `#e` は桁が表す正確な有理数を返し（浮動小数点数への丸めを経ません）、`#i` は正確な答えを浮動小数点数に変換します。接頭辞が重複しているか認識できないものであれば `#f` になります。

```scheme
(string->number "ff" 16) ; => 255
(string->number "#xff") ; => 255
(string->number "#e1.5") ; => 3/2
(string->number "#i5") ; => 5.0
(string->number "1/2") ; => 1/2
(string->number "abc") ; => #f
(string->number "-inf.0") ; => -inf.0
```
