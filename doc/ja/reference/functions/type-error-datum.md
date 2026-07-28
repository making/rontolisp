# type-error-datum

`(type-error-datum condition)`

`type-error` コンディションの `datum` スロット — 型が誤っていたオブジェクトです。対になるのは [`type-error-expected-type`](type-error-expected-type.md) です。

```lisp
(handler-case (error 'type-error :datum 3 :expected-type 'string)
  (type-error (e) (type-error-datum e))) ; => 3
```
