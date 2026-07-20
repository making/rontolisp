# input-stream-p

`(input-stream-p stream)`

Lite: `t` for any stream handle (every rontolisp stream answers both directions) and for the standard-output designator `t`, nil otherwise.

```lisp
(with-input-from-string (s "x")
  (input-stream-p s)) ; => T
```
