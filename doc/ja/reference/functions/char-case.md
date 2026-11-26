# char-upcase char-downcase

`(char-upcase character)` -- `(char-downcase character)`

1 つの文字の大文字または小文字形を返します。大文字・小文字の区別を持たない文字（数字や記号など）はそのまま返されます。`char-upcase` は小文字を大文字に変換し、`char-downcase` はその逆を行います。WASM バックエンドは ASCII の規則のみで大文字・小文字を変換します。文字でない引数は、演算子名を付けた `type-error` を通知します。

```lisp
(char-upcase #\a) ; => #\A
```
