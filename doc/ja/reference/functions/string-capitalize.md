# string-capitalize

`(string-capitalize string)`

各単語の最初の文字を大文字に、残りの文字を小文字にした新しい文字列を返します。ここで単語とは、他の文字で区切られた英数字の連なりを指します。元の文字列は変更されません。他の大文字小文字変換演算子と同様に、WASM バックエンドでは ASCII 規則のみで大文字小文字変換を行います。

```lisp
(string-capitalize "hello world") ; => "Hello World"
```
