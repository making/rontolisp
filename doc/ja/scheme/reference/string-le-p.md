# string<=?

`(string<=? string1 string2 string3 ...)`

文字列が辞書順で単調非減少なら `#t`、そうでなければ `#f` を返します。引数は 2 つ以上必要です。

```scheme
(string<=? "a" "a" "b") ; => #t
(string<=? "b" "a") ; => #f
```
