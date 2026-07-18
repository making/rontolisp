# input-stream-p

`(input-stream-p stream)`

Lite: `t` for any stream handle (every rontolisp stream answers both directions), nil otherwise.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(with-input-from-string (s "x")
  (input-stream-p s)) ; => t
```
