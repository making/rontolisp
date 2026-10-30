# set-syntax-from-char

`(set-syntax-from-char to-char from-char &optional (to-readtable *readtable*) from-readtable)`

`to-readtable` の `to-char` に、`from-readtable`(既定は標準リードテーブル)での `from-char` の構文(空白、構成文字、または関数とディスパッチテーブルを持つマクロ文字)を与え、`t` を返します。4つのバックエンドすべてで動作します。

ソースはすべてのバックエンドで現在のリードテーブルを使って読みます。インタプリタはプログラムとロードするファイルをフォーム単位で読み、コンパイルパスはコンパイル時の `eval-when` が残したリードテーブルで各フォームを読みます。実行時には、インタプリタの `read-from-string` と文字列ストリームに対する `read` / `read-delimited-list` がリードテーブルに従います。コンパイル済みバックエンドの実行時リーダーは標準の構文で読みます([リードテーブル](../../guides/readtables.md))。

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-syntax-from-char #\} #\))
  (read-delimited-list #\} (make-string-input-stream "a b}")))
; => (A B)
```
