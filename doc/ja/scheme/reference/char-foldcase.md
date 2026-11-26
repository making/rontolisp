# char-foldcase

`(char-foldcase char)`

`char` を Unicode の単純ケースフォールディングで畳み込んだ文字を返します。ふつうは小文字ですが、`ς` は `σ` に畳み込まれ、`İ` と `ı` は変わらず、チェロキー文字は大文字に畳み込まれます。Gauche はチェロキー文字を Unicode 8 より古い表で畳み込みます。

```scheme
(char-foldcase #\A) ; => #\a
(char-foldcase #\ς) ; => #\σ
```
