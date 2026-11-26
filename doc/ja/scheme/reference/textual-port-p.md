# textual-port?

`(textual-port? obj)`

`obj` がテキストポート（文字列ポートか標準のポート）なら `#t` を返します。ポートはテキストかバイナリのどちらか一方で、両方ではありません（Gauche のポートは両方です）。

```scheme
(textual-port? (open-input-string "x")) ; => #t
```
