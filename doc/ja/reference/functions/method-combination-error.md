# method-combination-error

`(method-combination-error format-control &rest args)`

メソッドコンビネーションの form から、メソッド全体について規則が拒むものについてエラーをシグナルします。メッセージは `format-control` と `args` から整形します。[`define-method-combination`](../macros/define-method-combination.md) を参照してください。

```lisp
(handler-case (method-combination-error "bad ~A" 2) (error () :refused)) ; => :REFUSED
```
