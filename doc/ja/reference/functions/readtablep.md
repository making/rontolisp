# readtablep

`(readtablep object)`

`object` がリードテーブルなら真を返します。4つのバックエンドすべてで動作します。

```lisp
(list (readtablep *readtable*) (readtablep nil)) ; => (T NIL)
```
