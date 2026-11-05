# make-condition

`(make-condition type &key initargs...)`

指定した型のコンディションオブジェクト(スロットを initarg から埋めた CLOS サブセットインスタンス。未指定スロットは `:initform` を取ります)を構築します。型は [`define-condition`](define-condition.md) で定義された型か `simple-error` などの組み込み型を指すシンボルで、クォートされたものでも計算されたものでもよく、`#'make-condition`(`funcall` や `apply` 経由)も同じものを受け取ります。それ以外は、それを datum、`condition` を期待型とする `type-error` です。コンパイルされたプログラムが見つけるクラスは、[`error`](error.md) が計算された datum から見つけるものです。インスタンスは [`error`](error.md)/[`signal`](signal.md)(コンディションオブジェクト designator)に渡せ、`typecase` でテストできます。

```lisp
(make-condition 'simple-error :format-control "something failed") ; => #<SIMPLE-ERROR :FORMAT-CONTROL "something failed" :FORMAT-ARGUMENTS NIL>
```

```console
CL-USER> (error (make-condition 'simple-error :format-control "something failed"))
Error: something failed
```
