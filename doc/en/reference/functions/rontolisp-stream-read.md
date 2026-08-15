# rontolisp:stream-read

`(rontolisp:stream-read stream)`

Returns a future settling to the stream's next chunk, or `nil` once the stream
is closed and drained (end of stream). Chunks are never `nil`, so a `nil`
result always means end of stream. A read on an open, empty stream stays
pending until a write arrives — that is the suspension an awaiting
asynchronous function parks on.

A chunk is whatever the producer wrote: a string for a guest-created stream,
and an `(unsigned-byte 8)` vector for every HTTP body stream (a fetched
reply's `:body`, a served request's `:raw-body`) — the octets exactly as they
came off the wire, so a body relayed as a response body crosses byte-exact.
[`rontolisp:read-all`](rontolisp-read-all.md) is the drain that decodes them
to text.

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

To drain all remaining chunks into one string in one await, use
[`rontolisp:read-all`](rontolisp-read-all.md) instead.

## Backend support

Asynchronous streams exist on the interpreter, the JVM backend and -- for the
request/response body streams `rontolisp:fetch` / `rontolisp:http-handler`
produce -- the `--component` WASM backend. A Preview 1 WASM module can hold a
stream value only when a host-backed body gives it one; where none can exist,
`rontolisp:streamp` answers `nil` and `rontolisp:stream-read` /
`rontolisp:stream-close` signal an error when called.
