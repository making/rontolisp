# type-error-expected-type

`(type-error-expected-type condition)`

`type-error` コンディションの `expected-type` スロット — datum が満たさなかった型指定子です。[`type-error-datum`](type-error-datum.md) も参照してください。

```lisp
(handler-case (error 'type-error :datum 3 :expected-type 'string)
  (type-error (e) (type-error-expected-type e))) ; => STRING
```
