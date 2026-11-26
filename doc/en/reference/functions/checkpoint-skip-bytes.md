# checkpoint:skip-bytes

`(checkpoint:skip-bytes stream n)`

Passes over `n` bytes of the byte `stream`, and returns `n`. When the stream tells its position, it seeks there instead of reading through -- a set drops any parked input -- so skipping a large unwanted tensor costs no I/O at all; otherwise (a socket, a pipe, any stream without a position) it walks in bounded reads through a 64 KB scratch buffer. Either way the tensor's bytes are never staged.

```console
CL-USER> (with-open-file (s "model.safetensors" :element-type '(unsigned-byte 8))
           (checkpoint:skip-bytes s 8))
8
```
