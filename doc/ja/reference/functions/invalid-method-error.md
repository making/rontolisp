# invalid-method-error

`(invalid-method-error method format-control &rest args)`

メソッドコンビネーションの form から、グループや規則が拒むメソッドについてエラーをシグナルします。メッセージはメソッドを示し、`format-control` と `args` から整形します。[`define-method-combination`](../macros/define-method-combination.md) を参照してください。

```lisp
(handler-case (invalid-method-error 1 "bad ~A" 2) (error () :refused)) ; => :REFUSED
```
