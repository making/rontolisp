# stream-external-format

`(stream-external-format stream)`

The external format the stream reads and writes: always `:utf-8`, the one format the reader and every writer use, so `open`'s `:external-format` option has nothing else to select.

```lisp
(with-output-to-string (s) (princ (stream-external-format s) s)) ; => "UTF-8"
```
