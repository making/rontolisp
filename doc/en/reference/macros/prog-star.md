# prog*

`(prog* (bindings...) {tag | form}...)`

Like [`prog`](prog.md) with sequential ([`let*`](let-star.md)-style) bindings: each init form sees the variables bound before it.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(prog* ((x 5) (y (* x 2)))
  (return (+ x y))) ; => 15
```
