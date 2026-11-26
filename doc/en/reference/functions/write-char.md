# write-char

`(write-char character &optional stream)`

Writes a single character to standard output (or to the given stream), returning the character. Writes the one-character string through [`write-string`](write-string.md) on every backend, so it works wherever string output does — including file streams, string streams and [socket handles](../../guides/tcp-sockets.md). On a [Gray stream](../../guides/gray-streams.md) instance it calls `rontolisp:stream-write-char`. It is a function: `#'write-char` can be passed to `funcall`, `apply` and `mapc`.

```lisp
(write-char #\o)
(write-char #\k)
(terpri)
```

```
ok
```
