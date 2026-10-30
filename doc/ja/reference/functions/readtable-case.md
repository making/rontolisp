# readtable-case

`(readtable-case readtable)`

リードテーブルがトークンのエスケープされていない文字を変換するケースです。`:upcase`(標準リードテーブルのケース)、`:downcase`、`:preserve`、`:invert`(エスケープされていない文字がすべて同じケースのトークンは反転し、それ以外はそのまま)のいずれかです。`(setf (readtable-case readtable) mode)` で設定します。4つのバックエンドすべてで動作します。ソースの読み込みはすべてのバックエンドでこのケースで変換し、実行時の読み込みで変換するのはインタプリタだけです。

```lisp
(readtable-case *readtable*) ; => :UPCASE
```

```lisp
(let ((*readtable* (copy-readtable nil)))
  (setf (readtable-case *readtable*) :invert)
  (list (read-from-string "foo") (read-from-string "Foo")))
; => (FOO |Foo|)
```
