# write-string

`(write-string string &optional stream &key start end)`

Writes the raw string contents -- without surrounding quotes and without a trailing newline -- and returns the string: `write-line` minus the newline. With no stream argument it writes to standard output; given an output stream (a file stream or a `with-output-to-string` string stream) it writes there instead. The `:start`/`:end` keywords bound the written substring (a `nil` `:end` means the string's length); the full string is still the return value. A CLOS instance extending rontolisp's Gray output-stream base class also works as the stream -- the write dispatches to `rontolisp:stream-write-string`. A [TCP or TLS socket handle](../../guides/tcp-sockets.md) works too: the string's UTF-8 bytes go on the wire immediately, with no trailing newline.

```lisp
(write-string "one, ")
(write-string "two")
```

```
one, two
```
