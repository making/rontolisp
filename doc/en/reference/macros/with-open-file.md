# with-open-file

`(with-open-file (stream filename options...) body...)`

Opens the file named by `filename`, binds the open stream to `stream`, evaluates the body forms with that binding, and closes the file afterwards, returning the value of the last body form. On the interpreter and the JVM the expansion wraps the body in [`unwind-protect`](../special-forms/unwind-protect.md), so the file is closed on every exit (normal return, an error signaled in the body, or a `return`/`return-from`); this holds on every backend, including wasm-GC since the exception-handling support landed (a `with-open-file` program compiles in EH mode there). The supported options are `:direction` -- `:input` (the default) or `:output` -- `:element-type` -- `'character` (the default, a text stream) or `'(unsigned-byte 8)` (a binary stream for `read-byte`/`write-byte`, with the unsized `'unsigned-byte` accepted as the same thing) -- and `:if-exists :append`, which opens an output stream WITHOUT truncating so every write lands at the end of an existing file. `:if-does-not-exist` and `:external-format` are accepted where they name the behavior already in place (`:create`/`:error` and `:utf-8`/`:default`). An option value may be COMPUTED: a function taking the options as arguments and passing them down is how a portable file wrapper opens a file, and such a value is read when the form runs and dispatched onto the matching literal shape. A literal value is still resolved at compile time, so the usual spelling compiles exactly as before. A value outside the supported set signals an error when the form runs. It expands into a plain `open`/`close` pair, so no special stream type is involved.

Because it touches the filesystem, `with-open-file` is shown here statically rather than as a runnable example:

```console
(with-open-file (s "out.txt" :direction :output)
  (write-line "hello" s))
(with-open-file (s "out.txt" :direction :input)
  (read-line s)) ; => "hello"
(with-open-file (s "out.bin" :direction :output :element-type '(unsigned-byte 8))
  (write-byte 255 s)) ; => 255
(with-open-file (s "out.txt" :direction :output :if-exists :append)
  (write-line "again" s))
(defun read-first-line (path element-type)
  (with-open-file (s path :direction :input :element-type element-type)
    (read-line s)))
```
