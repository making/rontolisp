# string->symbol

`(string->symbol string)`

名前が `string` のシンボルを、大文字小文字を保ったまま返します。同じ名前からは常に同じ（`eq?` な）シンボルが得られます。

識別子として読み戻せない名前のシンボルを、`write` は縦棒で囲んで書き出し、`display` は囲みません。

```scheme
(string->symbol "abc") ; => abc
(eq? 'abc (string->symbol "abc")) ; => #t
(string->symbol "hello world") ; => |hello world|
```
