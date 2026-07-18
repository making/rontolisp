# file-length

`(file-length stream)`

Lite: always returns nil (stream lengths are not tracked); portable callers take their unknown-length fallback path.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(with-input-from-string (s "abc")
  (file-length s)) ; => nil
```
