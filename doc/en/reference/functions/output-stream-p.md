# output-stream-p

`(output-stream-p stream)`

Lite: `t` for any stream handle (every rontolisp stream answers both directions) and for the standard-output designator `t`, nil otherwise. A [Gray stream](../../guides/gray-streams.md) instance is exact instead: it answers `t` only when its class descends from `rontolisp:fundamental-output-stream`.

```lisp
(with-output-to-string (s)
  (princ (output-stream-p s) s)) ; => "T"
```
