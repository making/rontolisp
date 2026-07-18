# file-length

`(file-length stream)`

Lite: always returns nil (stream lengths are not tracked); portable callers take their unknown-length fallback path.

```lisp
(with-input-from-string (s "abc")
  (file-length s)) ; => nil
```
