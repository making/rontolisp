# read-delimited-list

`(read-delimited-list char &optional stream recursive-p)`

`stream`(省略時は標準入力)から `char` までデータムを読み、`char` を消費して、リストとして返します。`char` は通常、終端マクロ文字なので、トークンはその直前で終わります。4つのバックエンドすべてで動作します。コンパイル済みバックエンドでは、トークンは標準の終端文字か空白でのみ終わります。

ソースはすべてのバックエンドで現在のリードテーブルを使って読みます。インタプリタはプログラムとロードするファイルをフォーム単位で読み、コンパイルパスはコンパイル時の `eval-when` が残したリードテーブルで各フォームを読みます。実行時には、インタプリタの `read-from-string` と文字列ストリームに対する `read` / `read-delimited-list` がリードテーブルに従います。コンパイル済みバックエンドの実行時リーダーは標準の構文で読みます([リードテーブル](../../guides/readtables.md))。

```lisp
(read-delimited-list #\) (make-string-input-stream "1 (2) x)")) ; => (1 (2) X)
```
