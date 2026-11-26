# string-ci<?

`(string-ci<? string1 string2 string3 ...)`

`string<?` と同じですが、`string-foldcase` を適用してから比較します。引数は 2 つ以上必要です。

```scheme
(string-ci<? "apple" "BANANA") ; => #t
(string-ci<? "B" "a") ; => #f
```
