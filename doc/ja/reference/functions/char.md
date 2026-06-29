# char schar

`(char string index)` -- `(schar string index)`

`string` の 0 始まりの `index` にある文字を返します。ここでは `char` と `schar` は同じ動作をします。Common Lisp では `schar` は単純文字列向けの変種ですが、rontolisp では同一に扱います。WASM バックエンドは文字列をバイト単位でインデックス参照するため、インデックス参照が正しく定義されるのは ASCII テキストのみです。

```lisp
(char "hello" 1) ; => #\e
```
