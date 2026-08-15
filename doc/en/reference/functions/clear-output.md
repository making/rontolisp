# clear-output

`(clear-output &optional stream)`

Discards whatever the designated output stream has buffered but not yet written, and returns nil. No backend buffers output in a way a program could throw away -- a write reaches the underlying sink as it is made -- so this validates its stream designator and does nothing else. It exists because the [Gray protocol](../../guides/gray-streams.md) names `stream-clear-output`, which a portable stream class implements: on a Gray instance the call reaches that method.

```lisp
(progn (princ "kept") (clear-output)) ; => NIL
```
