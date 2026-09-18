# string<?

`(string<? string1 string2 string3 ...)`

文字列が文字のコードポイントによる辞書順で狭義単調増加なら `#t`、そうでなければ `#f` を返します。引数は 2 つ以上必要です。大文字は小文字より前に並びます。

```scheme
(string<? "abc" "abd") ; => #t
(string<? "B" "a") ; => #t
```
