# char-ci<?

`(char-ci<? char1 char2 char3 ...)`

`char<?` と同じですが、`char-foldcase` を適用してから比較するので、英字は小文字として並びます。引数は 2 つ以上必要です。

```scheme
(char-ci<? #\a #\B #\c) ; => #t
(char-ci<? #\Z #\a) ; => #f
```
