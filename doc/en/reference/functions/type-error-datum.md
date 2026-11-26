# type-error-datum

`(type-error-datum condition)`

The `datum` slot of a `type-error` condition -- the object whose type was wrong. Its companion is [`type-error-expected-type`](type-error-expected-type.md).

```lisp
(handler-case (error 'type-error :datum 3 :expected-type 'string)
  (type-error (e) (type-error-datum e))) ; => 3
```
