# packagep

`(packagep object)`

`object` が登録済みパッケージを指すパッケージ
designator(文字列、シンボル、キーワード)なら `t`、そうでなければ
`nil`。signal
することはない: designator
でないものは型エラーではなく `nil` を返す。

```lisp
(list (packagep :cl) (packagep "CL-USER") (packagep :no-such-doc-pkg) (packagep 42)) ; => (T T NIL NIL)
```
