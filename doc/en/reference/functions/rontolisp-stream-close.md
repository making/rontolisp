# rontolisp:stream-close

`(rontolisp:stream-close stream)`

Closes the write end of an asynchronous stream and returns `nil`. Buffered
chunks stay readable; once they are drained,
[`rontolisp:stream-read`](rontolisp-stream-read.md) observes end of stream
(`nil`). Closing an already-closed stream is a no-op. A
[`rontolisp:stream-write`](rontolisp-stream-write.md) after the close signals
an error.

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "x")
  (rontolisp:stream-close s)
  (rontolisp:stream-close s))   ; => NIL
```

## Backend support

Asynchronous streams exist on the interpreter, the JVM backend and -- for the
request/response body streams `rontolisp:fetch` / `rontolisp:http-handler`
produce -- the `--component` WASM backend; Preview 1 WASM rejects the stream
operations at compile time.
