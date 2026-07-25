# with-open-stream

`(with-open-stream (var stream-form) body...)`

Binds `var` to the stream produced by `stream-form`, evaluates the body forms with that binding and closes the stream afterwards, returning the value of the last body form. It is [`with-open-file`](with-open-file.md) without the `open`: the stream is one you already have (a socket, a string stream, the result of a portable `open` call). On the interpreter and the JVM the body is wrapped in [`unwind-protect`](../special-forms/unwind-protect.md), so the stream closes on every exit; the WASM backends keep the close-after-body shape.

```lisp
(with-input-from-string (in "hello")
  (read-line in)) ; => "hello"
```

That is the shorthand; `with-open-stream` is the general form, shown statically because it needs a stream you opened yourself:

```console
(with-open-stream (s (open "f.txt" :direction :input))
  (read-line s)) ; => "hello"
```
