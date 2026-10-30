# set-macro-character

`(set-macro-character char function &optional non-terminating-p (readtable *readtable*))`

`char` を、リーダーが `(function stream char)` を呼ぶマクロ文字にし、`t` を返します。関数の値がデータムです。値を1つも返さない関数(`(values)`)は `char` をコメントの開始にします。`non-terminating-p` が真なら `char` はトークンの途中に現れてもかまいません。標準のマクロ関数を設定すると(`#\]` の下に `(get-macro-character #\))` など)、その文字は元の標準文字と同じように読まれます。4つのバックエンドすべてで動作します。

リードテーブルで読むのは今のところインタプリタのリーダーだけです。フォーム単位で読むプログラムとロードするファイル、`read-from-string`、文字列ストリームに対する `read` / `read-delimited-list` が対象です。コンパイルパスとコンパイル済みバックエンドの実行時リーダーは標準の構文で読みます([リードテーブル](../../guides/readtables.md))。

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-macro-character #\] (get-macro-character #\)))
  (set-macro-character #\[ (lambda (s c) (cons 'vector (read-delimited-list #\] s t))))
  (read-from-string "[1 2 (+ 1 2)]"))
; => (VECTOR 1 2 (+ 1 2))
```
