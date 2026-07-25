# find-package

`(find-package designator)`

lite 版: rontolisp にパッケージオブジェクトはないため、返される「パッケージ」は大文字化された正規パッケージ名のキーワードで、未知のパッケージには `nil` を返します。リテラルの指定子はコンパイル時に畳み込まれるため 4 つのバックエンドすべてで動作します。計算された指定子はインタプリタ専用です。

```lisp
(list (find-package :cl) (find-package "nope")) ; => (:CL NIL)
```
