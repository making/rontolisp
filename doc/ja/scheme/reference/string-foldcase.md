# string-foldcase

`(string-foldcase string)`

`string` を Unicode の完全なケースフォールディングで畳み込んだ新しい文字列を返します。`string-ci=?` などの `-ci` 付き文字列比較が比べるのも、`#!fold-case` が識別子に適用するのもこれです。Gauche ではなく Unicode に従い、`ẞ` は `ss` に畳み込まれ、`ı` は変わりません（Gauche では `ß` と `i`）。

```scheme
(string-foldcase "Straße") ; => "strasse"
(string-foldcase "ΧΑΟΣ") ; => "χαοσ"
```
