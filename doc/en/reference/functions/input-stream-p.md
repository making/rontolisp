# input-stream-p

`(input-stream-p stream)`

`t` when the stream can be read from. A string input stream, a file stream opened `:input` or `:io`, a socket and the standard input stream answer `t`; a string output stream, a file stream opened for output only and a file stream that has been closed answer nil. A synonym stream answers for the stream it currently forwards to. The designator `t` answers `t` for both directions, and anything that is not a stream answers nil. A [Gray stream](../../guides/gray-streams.md) instance answers `t` when its class descends from `rontolisp:fundamental-input-stream`.

```lisp
(with-input-from-string (s "x")
  (input-stream-p s)) ; => T
```

```lisp
(input-stream-p (make-string-output-stream)) ; => NIL
```
