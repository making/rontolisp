# char schar

`(char string index)` -- `(schar string index)`

`string` の 0 始まりの `index` にある文字を返します。ここでは `char` と `schar` は同じ動作をします。Common Lisp では `schar` は単純文字列向けの変種ですが、rontolisp では同一に扱います。WASM バックエンドは文字列をバイト単位でインデックス参照するため、インデックス参照が正しく定義されるのは ASCII テキストのみです。

どちらも `setf` の場所になります: `(setf (schar s i) c)` / `(setf (char s i) c)` は位置 `i` の文字を置き換えて `c` を返します。実行中のプログラムが確保した文字列 — たとえば [`make-string`](make-string.md) のバッファ — はその場で書き換えられます。文字列**リテラル**はどのバックエンドでも書き換えられません。リテラルはソース中の定数であり、それが現れるフォームを評価するたびに同じオブジェクトが返るため、書き込みは文字列を作り直して場所を再束縛します。したがってリテラルを保持する場合、文字列式は**変数**である必要があり (`(setf (char "abc" 0) #\Z)` のようにリテラルへ直接書いたり、アクセサ経由で取り出したリテラルへ書いたりするのはエラーです)、書き込み前に作られた別名参照はリテラル自身の内容のままになります。コンパイル系バックエンドでは、`copy-seq`/[`subseq`](subseq.md)、`concatenate 'string`、[`string-upcase`](string-upcase.md) ファミリ、`format nil`、[`with-output-to-string`](../macros/with-output-to-string.md)、[`read-line`](read-line.md) が作った文字列は `make-string` バッファと同じく可変であり、別名参照は書き込みを見ます。そこでまだ不変値を返す少数のプロデューサ (たとえば [`princ-to-string`](princ-to-string.md)) の結果にはこの作り直しと再束縛が適用され、別名参照が書き込みを見ることはありません。

```lisp
(char "hello" 1) ; => #\e
```
