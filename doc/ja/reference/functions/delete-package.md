# delete-package

`(delete-package package-designator)`

[`make-package`](make-package.md)
で作成したパッケージを削除する(登録とニックネームが落ちる)。読込/compile
時パッケージ(組込みまたは
[`defpackage`](../special-forms/defpackage.md)
によるもの)の削除は捕捉可能な `package-error`
を signal する(全 backend がそれに解決しているため)。何も指さない
designator も同様である。

```lisp
(make-package :doc-dp)
(delete-package :doc-dp) ; => T
(find-package :doc-dp) ; => NIL
```
