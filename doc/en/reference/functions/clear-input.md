# clear-input

`(clear-input &optional stream)`

Discards whatever the designated input stream has buffered but not yet delivered, and returns nil. No backend buffers input in a way a program could throw away -- a read reaches the underlying source as it is made -- so this validates its stream designator and does nothing else. It is [clear-output](clear-output.md) on the read side; nil and t both designate the standard input stream.

```lisp
(with-input-from-string (s "abc") (clear-input s)) ; => NIL
```
