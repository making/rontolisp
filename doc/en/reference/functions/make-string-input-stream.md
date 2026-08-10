# make-string-input-stream

`(make-string-input-stream string &optional start end)`

Returns a character input stream that reads from `string`, so `read-char`, `read-line`, `peek-char` and `read` consume it like any other input stream. It is the explicit form of the stream `with-input-from-string` binds, and is what you need when the stream has to outlive one expression -- when it is stored, or handed to a function that takes a stream. `start` and `end` bound the portion read, in characters.

```lisp
(let ((s (make-string-input-stream "one
two")))
  (list (read-line s) (read-line s) (read-line s nil :eof))) ; => ("one" "two" :EOF)
```
