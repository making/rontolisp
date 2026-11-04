# print-not-readable-object

`(print-not-readable-object condition)`

The object a `print-not-readable` condition was signalled for, the condition's `:object`. A `print-object` method signals it to refuse a readable print; the printer itself does not ([`with-standard-io-syntax`](../macros/with-standard-io-syntax.md)).

```lisp
(handler-case (error 'print-not-readable :object 1)
  (print-not-readable (e) (print-not-readable-object e))) ; => 1
```
