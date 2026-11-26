# set-dispatch-macro-character

`(set-dispatch-macro-character disp-char sub-char function &optional (readtable *readtable*))`

ディスパッチマクロ文字 `disp-char` の下の `sub-char`(大文字小文字を区別しません)のリーダー関数を `function` にし、`t` を返します。リーダーは `disp-char`、省略可能な10進数の引数、`sub-char` を読むと `(function stream sub-char argument)` を呼びます(数字がなければ `argument` は `nil`)。関数が返す値がデータムです。`sub-char` が10進数字ならエラーを通知します。4つのバックエンドすべてで動作します。

ソースはすべてのバックエンドで現在のリードテーブルを使って読みます。インタプリタはプログラムとロードするファイルをフォーム単位で読み、コンパイルパスはコンパイル時の `eval-when` が残したリードテーブルで各フォームを読みます。実行時には、インタプリタの `read-from-string` と文字列ストリームに対する `read` / `read-delimited-list` がリードテーブルに従います。コンパイル済みバックエンドの実行時リーダーは標準の構文で読みます([リードテーブル](../../guides/readtables.md))。

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-dispatch-macro-character #\# #\? (lambda (s c n) (list :q n (read s t nil t))))
  (read-from-string "#2?foo"))
; => (:Q 2 FOO)
```

標準リードテーブルには2つのディスパッチ構文が組み込まれています。`#N@(...)`(ironclad の S-box リテラル。指定した要素幅のベクタとして読みます)と `#L(...)`(iterate の番号付き引数ラムダ。`#L(list !2 !3)` は `#'(lambda (!1 !2 !3) (list !2 !3))` と読み、引数の数は本体が参照する最大の `!n`、または `#nL` で明示した数です)です。
