# package-name

`(package-name package-designator)`

指定されたパッケージの名前を文字列で返します。指示子はまず [`find-package`](find-package.md) で解決されるため、ニックネームからは正規名が返ります。未知の指示子はエラーをシグナルします。rontolisp のパッケージ値は正規名(大文字)のキーワードなので、名前文字列はそのキーワードの文字列です。

```lisp
(package-name (find-package :cl-user)) ; => "CL-USER"
```
