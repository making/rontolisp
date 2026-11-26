# char-ci>?

`(char-ci>? char1 char2 char3 ...)`

`char>?` と同じですが、`char-foldcase` を適用してから比較します。引数は 2 つ以上必要です。

```scheme
(char-ci>? #\c #\B #\a) ; => #t
(char-ci>? #\a #\A) ; => #f
```
