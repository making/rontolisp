# string=?

`(string=? string1 string2 string3 ...)`

すべての文字列の長さと文字が等しければ `#t`、そうでなければ `#f` を返します。引数は 2 つ以上必要です。大文字と小文字は区別されます。

```scheme
(string=? "abc" "abc" "abc") ; => #t
(string=? "abc" "ABC") ; => #f
```
