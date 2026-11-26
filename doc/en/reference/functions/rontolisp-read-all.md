# rontolisp:read-all

`(rontolisp:read-all stream)`

Returns a future settling to the remaining chunks of an asynchronous stream
drained into **one string**: string chunks (a guest-created stream) are
concatenated, and octet chunks -- the `(unsigned-byte 8)` vectors every HTTP
body stream answers, a fetched reply's `:body` and a served request's
`:raw-body` -- are joined and decoded as UTF-8, so a document-shaped consumer
reads text off a byte stream. A stream mixing the two kinds is an error. The
future settles once the stream reaches end of stream, so the producer side must
eventually call [`rontolisp:stream-close`](rontolisp-stream-close.md).

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "hello ")
  (rontolisp:stream-write s "world")
  (rontolisp:stream-close s)
  (rontolisp:await (rontolisp:read-all s)))   ; => "hello world"
```

It is the idiomatic way to drain a [`rontolisp:fetch`](rontolisp-fetch.md)
response body:

```console
(let ((r (rontolisp:await (rontolisp:fetch "https://example.com"))))
  (rontolisp:await (rontolisp:read-all (getf r :body))))
```

To take the chunks one at a time instead, use
[`rontolisp:stream-read`](rontolisp-stream-read.md); to forward a body without
reading it, answer the stream itself as a response body -- the transport drains
it byte-exact, nothing decodes on the way through.

A **string** passes straight through (the future settles to the string
itself): a body that has already fully arrived is its own drained value, so the
one drain spelling above works whatever shape `:body` took.

## Backend support

Asynchronous streams exist on the interpreter, the JVM backend and -- for the
request/response body streams `rontolisp:fetch` / `rontolisp:http-handler`
produce -- the `--component` WASM backend. A Preview 1 WASM module can hold a
stream value only when a host-backed body gives it one; where none can exist,
`rontolisp:streamp` answers `nil` and `rontolisp:stream-read` /
`rontolisp:stream-close` signal an error when called.
