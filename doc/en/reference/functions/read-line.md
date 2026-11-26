# read-line

`(read-line &optional stream eof-error-p eof-value recursive-p)`

Reads one line of text and returns it as a string with the trailing newline removed (only a line feed ends a line, and one carriage return just before it -- or before the end of input -- is dropped, so a CR alone stays in the line, and CRLF-terminated input such as HTTP over a [`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) socket reads as plain lines). With no argument it reads from standard input; given a stream opened by `open` or `with-open-file` it reads the next line from that stream. At end of input it returns `eof-value` (default `nil`) rather than signalling: `eof-error-p` defaults to `nil` here, where CL defaults it to `t`, and a true one signals `end-of-file`. `recursive-p` is accepted and ignored: it only matters to a reader macro's recursive read. The second value, `missing-newline-p`, is true when end of input -- not a newline -- ended the line, and true beside `eof-value` at end of input, as in CL; it is answered on every backend, through a [Gray stream](../../guides/gray-streams.md)'s `stream-read-line` too. Works in all three backends; unlike `read`, it returns the raw line without parsing it as an S-expression.

```console
(print (read-line))
```

Typing `hello world` on standard input makes `read-line` return the string `"hello world"`. When the input is exhausted it returns `nil`, which is the usual loop-termination test when reading a file line by line.

```lisp
(with-input-from-string (s (format nil "ab~%cd"))
  (list (multiple-value-list (read-line s))
        (multiple-value-list (read-line s))
        (multiple-value-list (read-line s nil :eof))))
;; => (("ab" NIL) ("cd" T) (:EOF T))
```
