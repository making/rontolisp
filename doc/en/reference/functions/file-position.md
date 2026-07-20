# file-position

`(file-position stream [position])`

Lite: always returns nil — streams do not support repositioning, so portable callers (which guard this with `ignore-errors`) take their non-seeking fallback path.

```lisp
(with-input-from-string (s "abc")
  (file-position s)) ; => NIL
```
