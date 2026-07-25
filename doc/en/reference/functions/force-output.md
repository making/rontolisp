# force-output

`(force-output &optional stream)`

Flushes the designated output stream's buffered bytes to its underlying sink and returns nil. With no argument (or `nil`/`t`) it flushes standard output. `finish-output` is the same operation here: once flushed, every rontolisp write is synchronous, so there is nothing further to wait for. Socket writes never buffer on any backend, which is why flushing a socket is a no-op that still costs nothing to write.

```lisp
(progn (princ "no newline yet") (force-output)) ; => NIL
```
