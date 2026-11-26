# read-char

`(read-char &optional stream eof-error-p eof-value recursive-p)`

Reads one character from `stream` (default: standard input) and returns it. The stream may be a file stream opened by `open`/`with-open-file`, a string input stream from `with-input-from-string`, or a [TCP or TLS socket handle](../../guides/tcp-sockets.md) -- on a socket the character is assembled from the wire's UTF-8 bytes, so `read-char` and `read-byte` can be mixed on one connection. At end of input it signals an `end-of-file` condition unless `eof-error-p` is `nil`, in which case it returns `eof-value` (default `nil`). Because the condition is the registered `end-of-file` class, the usual CL lexer shape -- a read loop wrapped in `(handler-case ... (end-of-file (e) ...))` -- terminates as written. A character is one Unicode code point on every backend, decoded from the stream's UTF-8. Malformed input reads as the JVM's decoder reads it, on every backend: a byte that starts no sequence is one U+FFFD (the replacement character), and so is a sequence that a wrong byte or the end of the input cuts short -- the wrong byte then starts the next character, so the bytes `41 E9 42` read as `A`, U+FFFD, `B`. `read-line`, `peek-char` and `read-sequence` decode the same way. `recursive-p` is accepted and ignored: it only matters to a reader macro's recursive read.

```lisp
(with-input-from-string (s "hi")
  (let* ((c1 (read-char s))
         (c2 (read-char s))
         (c3 (read-char s nil :end)))
    (list c1 c2 c3))) ; => (#\h #\i :END)
```
