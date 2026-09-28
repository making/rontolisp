# open-stream-p

`(open-stream-p stream)`

Returns `t` while the stream is open and `nil` after it has been closed -- the question the close-if-open idiom asks so it neither double-closes nor leaks. The answer is the same on every backend, for file, string and socket streams alike; a synonym stream answers what its target answers, and the `t` designator is always open. The interpreter and the JVM answer from the stream table (a [`close`](close.md) removes the entry) and additionally report `nil` for a socket closed from the other side; the WASM backends read a mark `close` sets on the stream value, since a descriptor is reused by the next `open`.

```lisp
(with-input-from-string (s "x") (open-stream-p s)) ; => T
(let ((s (make-string-output-stream)))
  (close s)
  (open-stream-p s)) ; => NIL
```

The close case touches a file, so it is shown statically:

```console
(let ((s (open "f.txt" :direction :input)))
  (open-stream-p s)   ; => T
  (close s)
  (open-stream-p s))  ; => NIL
```
