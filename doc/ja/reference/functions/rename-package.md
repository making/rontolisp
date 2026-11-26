# rename-package

`(rename-package package-designator new-name &optional new-nicknames)`

[`make-package`](make-package.md)
で作成したパッケージの名前を変更し、新しい名前でのパッケージを返す。ニックネームは
`new-nicknames`
で置換される(空リストの場合も古いものは落ちる)。読込/compile
時パッケージの変更、未知の
designator、別パッケージと衝突する新名(またはニックネーム)は捕捉可能な
`package-error` を signal する。

```lisp
(make-package :doc-rp :nicknames '(:drp))
(rename-package :drp :doc-rp2 :drp2) ; => :DOC-RP2
(package-nicknames :doc-rp2) ; => ("DRP2")
(delete-package :doc-rp2) ; => T
```
