# ldb-test

`(ldb-test bytespec integer)`

Load byte test: answers whether any bit of `integer`'s field named by the byte specifier `bytespec` (see [`byte`](byte.md)) is set -- `(not (zerop (ldb bytespec integer)))`.

```lisp
(ldb-test (byte 4 4) 255) ; => T
```
