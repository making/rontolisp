# checkpoint:skip-bytes

`(checkpoint:skip-bytes stream n)`

Passes over `n` bytes of the byte `stream`, in bounded reads through a 64 KB scratch buffer. Returns `n`. `file-position` answers `nil` on every backend -- a stream cannot seek -- so a reader walks its file front to back and skips what it was told not to load this way: the tensor's bytes cost their I/O and nothing else, and nothing is staged.

```console
CL-USER> (with-open-file (s "model.safetensors" :element-type '(unsigned-byte 8))
           (checkpoint:skip-bytes s 8))
8
```
