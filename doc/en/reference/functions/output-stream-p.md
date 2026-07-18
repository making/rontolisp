# output-stream-p

`(output-stream-p stream)`

Lite: `t` for any stream handle (every rontolisp stream answers both directions) and for the standard-output designator `t`, nil otherwise.

```lisp
(with-output-to-string (s)
  (princ (output-stream-p s))) ; => ""
```
