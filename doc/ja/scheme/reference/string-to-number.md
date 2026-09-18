# string->number

`(string->number string)` `(string->number string radix)`

`string` を基数 `radix`（既定値 10）の数として解析して返します。数として読めなければ `#f` を返します。符号と指数を任意に伴う整数、分数、小数を読めます。基数接頭辞（`#x`、`#b` など）、正確性接頭辞、`+inf.0` / `+nan.0` は読めず `#f` になります。

```scheme
(string->number "ff" 16) ; => 255
(string->number "1/2") ; => 1/2
(string->number "abc") ; => #f
```
