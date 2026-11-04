# package-name

`(package-name package-designator)`

指定されたパッケージの名前を文字列で返します。指示子はまず [`find-package`](find-package.md) で解決されるため、ニックネームからは正規名が返ります。未知の指示子はエラーをシグナルします。rontolisp のパッケージ値は正規名(大文字)のキーワードなので、名前文字列はそのキーワードの文字列です。ただし標準の 2 パッケージは別で、`:cl` の名前は `"COMMON-LISP"`、`:cl-user` の名前は `"COMMON-LISP-USER"` です。

```lisp
(package-name (find-package :cl-user)) ; => "COMMON-LISP-USER"
```
