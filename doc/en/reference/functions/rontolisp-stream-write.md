# rontolisp:stream-write

`(rontolisp:stream-write stream chunk)`

Appends `chunk` (which must not be `nil`) to an asynchronous stream and
returns a future that settles when the stream accepted it, so a producer can
flow-control by [`rontolisp:await`](../special-forms/rontolisp-await.md)ing
each write.

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:await (rontolisp:stream-write s "chunk"))
  (rontolisp:stream-close s)
  (rontolisp:await (rontolisp:stream-read s)))   ; => "chunk"
```

Writing to a stream whose write end was closed with
[`rontolisp:stream-close`](rontolisp-stream-close.md) signals an error:

```console
> (let ((s (rontolisp:make-stream)))
    (rontolisp:stream-close s)
    (rontolisp:stream-write s "x"))
stream-write: the stream is closed
```

## Backend support

Guest-created streams (`rontolisp:make-stream` / `rontolisp:stream-write`)
exist on the interpreter and the JVM backend today; the WASM backends reject
them at compile time (a `--component` program's streams come from
`rontolisp:fetch` / `rontolisp:http-handler` bodies).
