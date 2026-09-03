# checkpoint:stage-float-bits

`(checkpoint:stage-float-bits stream count format dst &key (start 0))`

Reads `count` little-endian 16-bit words from the byte `stream`, at its current position, as `format` bit patterns -- `:float16` or `:bfloat16` -- and widens them into `dst`, a packed float array of any rank, row-major from flat index `start`. Returns `dst`.

The words are staged in chunks of a million elements through one `(unsigned-byte 16)` buffer the package reuses for every tensor of every file, each chunk widened with [`rontolisp:widen-float-bits`](widen-float-bits.md) at its own `:start` offset. A packed integer vector costs eight bytes an element on the interpreter and the JVM, so a tensor staged whole would cost four times its file size in temporaries; taking the stream rather than a staged vector is what makes it impossible to get that wrong.

```console
CL-USER> (with-open-file (s "model.safetensors" :element-type '(unsigned-byte 8))
           (checkpoint:skip-bytes s data-start)
           (checkpoint:stage-float-bits s (* 2048 2048) :bfloat16
                                        (checkpoint:make-tensor '(2048 2048) 'single-float)))
#<packed single-float array (2048 2048)>
```

## Backend support

Every backend that has a filesystem, once [`rontolisp:widen-float-bits`](widen-float-bits.md) is there: the interpreter and a compiled `.class`/`.jar` today.
