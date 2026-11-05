# print-not-readable-object

`(print-not-readable-object condition)`

`print-not-readable` コンディションがシグナルされた対象のオブジェクト、つまりコンディションの `:object` です。プリンタは `*print-readably*` の下で読み戻せる表記を持たない値に対してシグナルします([`with-standard-io-syntax`](../macros/with-standard-io-syntax.md))。`print-object` メソッドが読み戻せる印字を拒むためにシグナルすることもできます。

```lisp
(handler-case (error 'print-not-readable :object 1)
  (print-not-readable (e) (print-not-readable-object e))) ; => 1
```
