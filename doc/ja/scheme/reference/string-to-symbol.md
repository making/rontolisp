# string->symbol

`(string->symbol string)`

名前が `string` のシンボルを、大文字小文字を保ったまま返します。同じ名前からは常に同じ（`eq?` な）シンボルが得られます。

仕様との差異: `|...|` 識別子がないため、`(string->symbol "hello world")` のように通常の識別子として書けない名前のシンボルは、縦棒なしで `hello world` と書き出されます。

```scheme
(string->symbol "abc") ; => abc
(eq? 'abc (string->symbol "abc")) ; => #t
```
