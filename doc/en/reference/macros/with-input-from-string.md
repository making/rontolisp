# with-input-from-string

`(with-input-from-string (stream string) body...)`

Binds `stream` to an input stream reading from `string`, evaluates the body forms, and returns the value of the last one. `read-line` consumes the string line by line and returns nil at the end; `read` parses one datum per line (like `read` on a file stream, it is line-oriented, so the rest of a line after the first datum is skipped). Works in all three backends.

```lisp
(with-input-from-string (s "(1 2 3)")
  (read s)) ; => (1 2 3)
```
