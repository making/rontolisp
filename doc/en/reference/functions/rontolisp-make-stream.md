# rontolisp:make-stream

`(rontolisp:make-stream)`

Creates a fresh open asynchronous stream. One value owns both the read and the
write end: producers append chunks with
[`rontolisp:stream-write`](rontolisp-stream-write.md) and finish with
[`rontolisp:stream-close`](rontolisp-stream-close.md); consumers take chunks
with [`rontolisp:stream-read`](rontolisp-stream-read.md) (each read yields a
future) or drain the string chunks in one go with
[`rontolisp:read-all`](rontolisp-read-all.md).

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "hello ")
  (rontolisp:stream-write s "world")
  (rontolisp:stream-close s)
  (rontolisp:await (rontolisp:read-all s)))   ; => "hello world"
```

## Backend support

Guest-created streams (`rontolisp:make-stream` / `rontolisp:stream-write`)
exist on the interpreter and the JVM backend today; the WASM backends reject
them at compile time (a `--component` program's streams come from
`rontolisp:fetch` / `rontolisp:http-handler` bodies).
