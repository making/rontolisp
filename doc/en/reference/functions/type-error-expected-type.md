# type-error-expected-type

`(type-error-expected-type condition)`

The `expected-type` slot of a `type-error` condition -- the type specifier the datum failed. See [`type-error-datum`](type-error-datum.md).

```lisp
(handler-case (error 'type-error :datum 3 :expected-type 'string)
  (type-error (e) (type-error-expected-type e))) ; => STRING
```
