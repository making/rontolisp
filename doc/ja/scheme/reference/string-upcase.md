# string-upcase

`(string-upcase string)`

`string` を Unicode の完全な大文字マッピングで変換した新しい文字列を返します。結果が引数より長くなることがあります（`ß` は `SS` になります）。

```scheme
(string-upcase "Hello") ; => "HELLO"
(string-upcase "straße") ; => "STRASSE"
```
