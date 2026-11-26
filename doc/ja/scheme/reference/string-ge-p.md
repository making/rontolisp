# string>=?

`(string>=? string1 string2 string3 ...)`

文字列が辞書順で単調非増加なら `#t`、そうでなければ `#f` を返します。引数は 2 つ以上必要です。

```scheme
(string>=? "b" "a") ; => #t
(string>=? "a" "b") ; => #f
```
