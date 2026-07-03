# with-open-file

`(with-open-file (stream filename options...) body...)`

Opens the file named by `filename`, binds the open stream to `stream`, evaluates the body forms with that binding, and closes the file afterwards (even if the body exits early), returning the value of the last body form. The supported options are `:direction` -- the literal keyword `:input` (the default) or `:output` -- and `:element-type` -- the literal `'character` (the default, a text stream) or `'(unsigned-byte 8)` (a binary stream for `read-byte`/`write-byte`). Both must be literal so the compilers can pick the file mode statically. It expands into a plain `open`/`close` pair, so no special stream type is involved.

Because it touches the filesystem, `with-open-file` is shown here statically rather than as a runnable example:

```console
(with-open-file (s "out.txt" :direction :output)
  (write-line "hello" s))
(with-open-file (s "out.txt" :direction :input)
  (read-line s)) ; => "hello"
(with-open-file (s "out.bin" :direction :output :element-type '(unsigned-byte 8))
  (write-byte 255 s)) ; => 255
```
