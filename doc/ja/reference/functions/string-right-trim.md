# string-right-trim

`(string-right-trim character-bag string)`

`character-bag` に含まれる末尾の文字を後方からのみ取り除いた新しい文字列を返します。左端はそのまま残ります。`character-bag` は文字の任意のシーケンス (文字列・リスト・ベクタ) で、その要素が取り除く対象の集合を構成し、bag に含まれない最後の文字でトリミングは停止します。`string` は [文字列指定子](string.md) なので、`(string-right-trim "O" '|FOO|)` は `"F"` を返します。

```lisp
(string-right-trim "x" "hixx") ; => "hi"
```
