# listen

`(listen &optional stream)`

Returns `t` when a character or byte is immediately available on the designated input stream (standard input with no argument), `nil` otherwise. It never blocks: this is the `available()`/`ready()` question, which is what a protocol implementation asks to detect unexpected data -- cl-postgres uses it to spot an oversized SSL response.

On the interpreter and the JVM a socket answers from the kernel receive buffer, so the answer is exact. On the WASM `--component` backend the answer is exact for a socket too, but for a different reason: it reports whether the socket's already-read chunk still holds unconsumed bytes -- bytes still waiting host-side are not observable there without blocking. Preview 1 WASM has no non-blocking probe at all and rejects `listen` at compile time.

```console
(listen)          ; => NIL, with no pending standard input
```
