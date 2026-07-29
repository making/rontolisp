# make-synonym-stream

`(make-synonym-stream symbol)`

Returns the stream designator that `symbol` -- a special variable naming a stream, such as `*standard-output*` -- currently holds. The usual shape is a `defvar` whose default output goes wherever the standard stream goes.

Lite, and worth knowing before you rely on it: Common Lisp's synonym stream forwards every operation to the symbol's value *at the time of that operation*, whereas rontolisp resolves the symbol **once**, where the stream is built. Rebinding the variable afterwards therefore does not redirect a synonym stream that was constructed earlier; pass the stream explicitly, or bind `*standard-output*` and use the stream-argument-less print family, when you need the redirect.

```lisp
(defvar *report-output* (make-synonym-stream '*standard-output*))
(write-string "hello" *report-output*) ; => "hello"
```
