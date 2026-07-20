# rontolisp:stream-read

`(rontolisp:stream-read stream)`

Returns a future settling to the stream's next chunk, or `nil` once the stream
is closed and drained (end of stream). Chunks are never `nil`, so a `nil`
result always means end of stream. A read on an open, empty stream stays
pending until a write arrives — that is the suspension an awaiting
asynchronous function parks on.

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "a")
  (rontolisp:stream-close s)
  (print (rontolisp:await (rontolisp:stream-read s)))
  (print (rontolisp:await (rontolisp:stream-read s))))
```

```
"a"
NIL
```

To concatenate all remaining string chunks in one await, use
[`rontolisp:read-all`](rontolisp-read-all.md) instead.

## Backend support

Asynchronous streams exist on the interpreter, the JVM backend and -- for the
request/response body streams `rontolisp:fetch` / `rontolisp:http-handler`
produce -- the `--component` WASM backend; Preview 1 WASM rejects the stream
operations at compile time.
