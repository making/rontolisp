# rontolisp:read-all

`(rontolisp:read-all stream)`

Returns a future settling to the concatenation of all remaining *string*
chunks of an asynchronous stream (a non-string chunk is an error). The future
settles once the stream reaches end of stream, so the producer side must
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
[`rontolisp:stream-read`](rontolisp-stream-read.md).

A **string** passes straight through (the future settles to the string
itself): a body that has already fully arrived — a `--host-fetch` reactor's
`:body` — is its own drained value, so the one drain spelling above works
whatever shape `:body` took.

## Backend support

Asynchronous streams exist on the interpreter, the JVM backend and -- for the
request/response body streams `rontolisp:fetch` / `rontolisp:http-handler`
produce -- the `--component` WASM backend. A Preview 1 WASM module can hold a
stream value only when a host-backed body gives it one; where none can exist,
`rontolisp:streamp` answers `nil` and `rontolisp:stream-read` /
`rontolisp:stream-close` signal an error when called.
