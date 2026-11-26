# package-error-package

`(package-error-package condition)`

`package-error` コンディションから原因のパッケージ
designator を取り出す --
[`make-package`](make-package.md)
などが signal 時に格納した値である。パッケージを指さない失敗(空名)の場合は
`nil` である。

```lisp
(handler-case (make-package :cl)
  (package-error (c) (package-error-package c))) ; => :CL
```
