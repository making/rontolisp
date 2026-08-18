# input-stream-p

`(input-stream-p stream)`

Lite: `t` for any stream handle (every rontolisp stream answers both directions) and for the standard-output designator `t`, nil otherwise. A [Gray stream](../../guides/gray-streams.md) instance is exact instead: it answers `t` only when its class descends from `rontolisp:fundamental-input-stream`.

```lisp
(with-input-from-string (s "x")
  (input-stream-p s)) ; => T
```
