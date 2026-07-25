# finish-output

`(finish-output &optional stream)`

The same operation as [`force-output`](force-output.md): flushes the designated output stream and returns nil. Common Lisp distinguishes the two (`finish-output` waits for the sink to accept the data), but every rontolisp write is synchronous once flushed, so both names name one behavior.

```lisp
(progn (princ "buffered") (finish-output)) ; => NIL
```
