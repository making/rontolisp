# make-synonym-stream

`(make-synonym-stream symbol)`

Returns a stream designator that forwards to the stream `symbol` -- a special variable naming a stream, such as `*standard-output*` -- holds. The usual shape is a `defvar` whose default output goes wherever the standard stream goes.

`(make-synonym-stream '*standard-output*)` and `(make-synonym-stream '*standard-input*)` are the full Common Lisp behaviour: each answers the `nil` designator, and every output (resp. input) operation resolves `nil` through the *current* `*standard-output*` / `*standard-input*`, so rebinding the variable afterwards **does** redirect a synonym stream that was constructed earlier.

For any other symbol the result is lite: rontolisp resolves the symbol **once**, where the stream is built, instead of on every operation. Rebinding that variable afterwards therefore does not redirect the synonym stream; pass the stream explicitly, or bind `*standard-output*` and use the stream-argument-less print family, when you need the redirect.

```lisp
(defvar *report-output* (make-synonym-stream '*standard-output*))
(write-string "hello" *report-output*) ; => "hello"
```
