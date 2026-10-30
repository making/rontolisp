# make-dispatch-macro-character

`(make-dispatch-macro-character char &optional non-terminating-p (readtable *readtable*))`

`char` を空のディスパッチテーブルを持つディスパッチマクロ文字にし、`t` を返します。テーブルは [`set-dispatch-macro-character`](set-dispatch-macro-character.md) で埋めます。`non-terminating-p` が真なら `char` はトークンの途中に現れてもかまいません。4つのバックエンドすべてで動作します。

リードテーブルで読むのは今のところインタプリタのリーダーだけです。フォーム単位で読むプログラムとロードするファイル、`read-from-string`、文字列ストリームに対する `read` / `read-delimited-list` が対象です。コンパイルパスとコンパイル済みバックエンドの実行時リーダーは標準の構文で読みます([リードテーブル](../../guides/readtables.md))。

```lisp
(let ((*readtable* (copy-readtable nil)))
  (make-dispatch-macro-character #\!)
  (set-dispatch-macro-character #\! #\v (lambda (s c n) (vector (read s t nil t))))
  (read-from-string "!v42"))
; => #(42)
```
