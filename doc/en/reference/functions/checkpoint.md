# checkpoint Package Functions

The `checkpoint` package stages a published model's tensors into packed float
arrays: the half of reading a checkpoint that every file format shares. The
[`safetensors`](safetensors.md) reader is written over it, and so is the GGUF
reader. It is written in rontolisp itself and loaded on first use like `geom`;
it reaches for nothing but the byte-stream primitives and
`rontolisp:widen-float-bits`, so it runs on every backend
that has a filesystem. It is **not part of Common Lisp**; reference its names with
the `checkpoint:` qualifier.

Three facts shape it. A stream cannot reposition (`file-position` answers `nil`),
so a reader walks its file front to back and passes over what it does not want
with `checkpoint:skip-bytes`. A packed `(unsigned-byte 16)` vector costs eight
bytes an element on the interpreter and the JVM, so f16 / bf16 bits are staged in
chunks of a million elements through one reused buffer -- `stage-float-bits`
takes the STREAM, never a whole staged tensor. And `make-array :element-type`
answers a boxed array for a type it does not know, so `make-tensor` is the one
allocation path and checks what it got.

| Function | Example | Result |
|----------|---------|--------|
| `checkpoint:make-tensor` | `(checkpoint:make-tensor '(2 3) 'single-float)` | a packed float array of that shape, verified packed |
| `checkpoint:stage-float-bits` | `(checkpoint:stage-float-bits s 4096 :bfloat16 dst)` | 4096 bf16 words read off the stream and widened into `dst` |
| `checkpoint:stage-float32` | `(checkpoint:stage-float32 s dst)` | an F32 tensor read straight into `dst` |
| `checkpoint:skip-bytes` | `(checkpoint:skip-bytes s 1048576)` | a megabyte passed over in bounded reads |
