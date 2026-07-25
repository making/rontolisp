# open-stream-p

`(open-stream-p stream)`

Returns `t` while the stream handle names an open stream and `nil` after it has been closed -- the question the close-if-open idiom asks so it neither double-closes nor leaks. The interpreter and the JVM answer from the stream table (a [`close`](close.md) removes the entry) and additionally report `nil` for a socket closed from the other side.

On the WASM `--component` backend a socket answers exactly the same way (the socket table is Lisp state there). Any other stream designator answers `t` when it is non-nil: Preview 1 keeps no per-descriptor open/closed record.

```lisp
(with-input-from-string (s "x") (open-stream-p s)) ; => T
```

The close case touches a file, so it is shown statically:

```console
(let ((s (open "f.txt" :direction :input)))
  (open-stream-p s)   ; => T
  (close s)
  (open-stream-p s))  ; => NIL
```
