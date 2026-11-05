# close

`(close stream)`

Closes a stream previously returned by `open` and returns `t`. After closing, an operator that reads or writes the stream, or flushes it, signals a `stream-error` reporting `#<STREAM> is closed` on every backend; closing it again does nothing and returns `t`. A standard stream, `*standard-output*` included, survives the close, which returns `t`. Anything that is not a stream, an instance of a class or structure that is none included, signals a `type-error` whose expected type is `stream`. Works on every backend. In most code you should prefer the `with-open-file` macro, which arranges the matching `close` for you even when the body exits early.

```console
(let ((s (open "out.txt" :output)))
  (write-line "done" s)
  (close s))
```

Here the stream is opened for output, written to, and then closed; the `close` call flushes and releases the file descriptor.
