# rontolisp:streamp

`(rontolisp:streamp value)`

Returns `t` if `value` is an *asynchronous* stream — as returned by
[`rontolisp:make-stream`](rontolisp-make-stream.md) or found in a
[`rontolisp:fetch`](rontolisp-fetch.md) response body — and `nil` otherwise.

```lisp
(rontolisp:streamp (rontolisp:make-stream))   ; => t
(rontolisp:streamp 42)                        ; => nil
```

This is a different symbol from `cl:streamp`, the file-stream predicate: each
answers `nil` for the other's streams.

```lisp
(streamp (rontolisp:make-stream))   ; => nil
```

An asynchronous stream is an opaque value: it has no reader syntax and prints
as `#<STREAM>`.

## Backend support

Asynchronous streams exist on the interpreter, the JVM backend and -- for the
request/response body streams `rontolisp:fetch` / `rontolisp:http-handler`
produce -- the `--component` WASM backend; Preview 1 WASM rejects the stream
operations at compile time.
