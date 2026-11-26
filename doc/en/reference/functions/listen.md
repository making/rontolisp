# listen

`(listen &optional stream)`

Returns `t` when a character or byte is immediately available on the designated input stream (standard input with no argument), `nil` otherwise. It never blocks: this is the `available()`/`ready()` question, which is what a protocol implementation asks to detect unexpected data -- cl-postgres uses it to spot an oversized SSL response.

On the interpreter, the JVM and in a native executable a socket answers from the kernel receive buffer (in an executable, after the bytes left in the chunk already read), so the answer is exact. On the WASM `--component` backend a socket answers what the host can hand over at once: bytes left in the chunk already read, or a read the host answers without waiting -- the probe takes such a read back when the host has nothing, so it consumes nothing. Bytes that still need the host to run first (a peer's data on its way) show only after the program next waits -- in a `sleep`, a blocking read, a `usocket:wait-for-input` poll -- so poll with a wait between probes rather than in a tight loop. A socket at the peer's close answers `nil` everywhere. A string input stream answers whether a character remains on every backend (an unread character counts); on the WASM backends any other stream signals an error when the call runs, since no non-blocking probe exists for it there.

```console
(listen)          ; => NIL, with no pending standard input
```
