# output-stream-p

`(output-stream-p stream)`

`t` when the stream can be written to. A string output stream, a file stream opened for output (`:output`, `:append`, `:overwrite`) or `:io`, a socket, the standard output stream and `*error-output*` answer `t`; a string input stream, a file stream opened `:input` and a file stream that has been closed answer nil. A synonym stream answers for the stream it currently forwards to. The designator `t` answers `t` for both directions, and anything that is not a stream answers nil. A [Gray stream](../../guides/gray-streams.md) instance answers `t` when its class descends from `rontolisp:fundamental-output-stream`.

```lisp
(with-output-to-string (s)
  (princ (output-stream-p s) s)) ; => "T"
```

```lisp
(output-stream-p (make-string-input-stream "abc")) ; => NIL
```
