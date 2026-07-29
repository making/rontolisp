# make-string-output-stream

`(make-string-output-stream)`

Returns a fresh string output stream: an output stream that accumulates everything written to it, to be read back with `get-output-stream-string`. It is the explicit form of what `with-output-to-string` builds, and is what a `defstruct` slot `:initform` needs when the stream has to outlive one expression. CL's `:element-type` keyword argument is accepted and ignored -- every rontolisp stream is a character stream.

```lisp
(let ((s (make-string-output-stream)))
  (write-string "ab" s)
  (princ 12 s)
  (get-output-stream-string s)) ; => "ab12"
```
