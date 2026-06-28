# close

`(close stream)`

Closes a stream previously returned by `open` and returns `t`. After closing, the stream handle is no longer valid and must not be used for further `read`/`read-line`/`write-line` calls. Works in all three backends. In most code you should prefer the `with-open-file` macro, which arranges the matching `close` for you even when the body exits early.

```console
(let ((s (open "out.txt" :output)))
  (write-line "done" s)
  (close s))
```

Here the stream is opened for output, written to, and then closed; the `close` call flushes and releases the file descriptor.
