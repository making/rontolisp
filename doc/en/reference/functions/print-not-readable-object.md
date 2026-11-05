# print-not-readable-object

`(print-not-readable-object condition)`

The object a `print-not-readable` condition was signalled for, the condition's `:object`. The printer signals it for a value with no readable syntax under `*print-readably*` ([`with-standard-io-syntax`](../macros/with-standard-io-syntax.md)), and a `print-object` method may signal it to refuse a readable print.

```lisp
(handler-case (error 'print-not-readable :object 1)
  (print-not-readable (e) (print-not-readable-object e))) ; => 1
```
