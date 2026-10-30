# copy-readtable

`(copy-readtable &optional (from-readtable *readtable*) to-readtable)`

リードテーブルの複製を返します。ケースと、文字に与えたマクロ文字・ディスパッチテーブル・構文をすべて複製します。`from-readtable` の `nil` は標準リードテーブルを指すため、`(copy-readtable nil)` は新しい標準リードテーブルです。`to-readtable` を渡すと、そのリードテーブルを上書きして返します。4つのバックエンドすべてで動作します。

```lisp
(readtablep (copy-readtable nil)) ; => T
```
