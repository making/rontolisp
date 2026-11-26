# package-nicknames

`(package-nicknames package-designator)`

指定パッケージのニックネーム文字列をソート順で返す -- 存在しない場合は空である。未知の
designator は捕捉可能な `package-error` を signal する。

```lisp
(make-package :doc-pn :nicknames '(:dpn :dpn2))
(package-nicknames :doc-pn) ; => ("DPN" "DPN2")
(delete-package :doc-pn) ; => T
```
