# file-position

`(file-position stream [position])`

Lite: always returns nil — streams do not support repositioning, so portable callers (which guard this with `ignore-errors`) take their non-seeking fallback path.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(with-input-from-string (s "abc")
  (file-position s)) ; => nil
```
