# char schar

`(char string index)` -- `(schar string index)`

`string` の 0 始まりの `index` にある文字を返します。ここでは `char` と `schar` は同じ動作をします。Common Lisp では `schar` は単純文字列向けの変種ですが、rontolisp では同一に扱います。WASM バックエンドは文字列をバイト単位でインデックス参照するため、インデックス参照が正しく定義されるのは ASCII テキストのみです。

どちらも `setf` の場所になります: `(setf (schar s i) c)` / `(setf (char s i) c)` は位置 `i` の文字を置き換えて `c` を返します。インタプリタは文字列をその場で書き換えます。コンパイル系バックエンドは文字列を作り直して再束縛するため、文字列式は**変数**である必要があり、書き込み前に作られた別名参照は古い内容のままになります。

```lisp
(char "hello" 1) ; => #\e
```
