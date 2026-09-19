# string-downcase

`(string-downcase string)`

`string` を Unicode の完全な小文字マッピングで変換した新しい文字列を返します。語末の大文字シグマは語末形 `ς` になり、`İ` は `i` と結合用上点の 2 文字になります。

```scheme
(string-downcase "HELLO") ; => "hello"
(string-downcase "ΧΑΟΣ") ; => "χαος"
```
