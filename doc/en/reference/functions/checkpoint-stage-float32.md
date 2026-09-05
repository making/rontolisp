# checkpoint:stage-float32

`(checkpoint:stage-float32 stream dst)`

Reads an F32 tensor -- the bytes of `dst`, a packed single-float array of any rank, little-endian -- from the byte `stream` at its current position, in one `read-sequence`. Returns `dst`. The counterpart of [`checkpoint:stage-float-bits`](checkpoint-stage-float-bits.md) for the width that needs no conversion.

A `bfloat16` `dst` is the one shape whose bytes these are not, so the words are staged a chunk at a time and **narrowed as they stream** -- never by materializing the whole f32 tensor first, which at a billion parameters is a 4.4 GB transient.

```console
CL-USER> (with-open-file (s "model.safetensors" :element-type '(unsigned-byte 8))
           (checkpoint:skip-bytes s data-start)
           (checkpoint:stage-float32 s (checkpoint:make-tensor 2048 'single-float)))
#f(0.0234375 -0.0078125 ...)
```
