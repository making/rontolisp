# print-not-readable-object

`(print-not-readable-object condition)`

`print-not-readable` コンディションがシグナルされた対象のオブジェクト、つまりコンディションの `:object` です。`print-object` メソッドが読み戻せる印字を拒むときにシグナルします。プリンタ自身はシグナルしません([`with-standard-io-syntax`](../macros/with-standard-io-syntax.md))。

```lisp
(handler-case (error 'print-not-readable :object 1)
  (print-not-readable (e) (print-not-readable-object e))) ; => 1
```
