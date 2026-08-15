# make-synonym-stream

`(make-synonym-stream symbol)`

Returns a stream that forwards every operation to the stream the *special variable* `symbol` holds **at the time of that operation** -- so rebinding the variable afterwards redirects a synonym stream that was constructed earlier. The usual shape is a `defvar` whose default output follows whatever the standard stream currently is.

The result is a stream *value*, not a designator: it is true, [`streamp`](streamp.md) / [`input-stream-p`](input-stream-p.md) / [`output-stream-p`](output-stream-p.md) answer `t` for it, [`synonym-stream-symbol`](synonym-stream-symbol.md) reads the symbol back, and [`close`](close.md) closes the synonym (which is nothing to do) and answers `t`.

A Gray stream may sit on either side of it: a synonym stream handed to a [Gray](../../guides/gray-streams.md) output stream is written through, and a synonym whose variable holds a Gray stream reaches it.

```lisp
(defvar *report-output* (make-synonym-stream '*standard-output*))
(write-string "hello" *report-output*) ; => "hello"
```
