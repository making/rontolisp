# string-ci=?

`(string-ci=? string1 string2 string3 ...)`

`string=?` と同じですが、`string-foldcase` を適用してから比較するので、`ß` と `ss` は等しくなります。引数は 2 つ以上必要です。

```scheme
(string-ci=? "Hello" "hELLO") ; => #t
(string-ci=? "Straße" "STRASSE") ; => #t
(string-ci=? "a" "b") ; => #f
```
