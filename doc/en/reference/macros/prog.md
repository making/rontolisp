# prog

`(prog (bindings...) {tag | form}...)`

Binds the variables like [`let`](../special-forms/let.md), then runs the body as a [`tagbody`](../special-forms/tagbody.md) inside a block: [`go`](../special-forms/go.md) jumps between the body's tags and `(return value)` exits the `prog` with `value`. Falling off the end returns nil.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(prog ((n 5) (acc 1))
 top
  (when (<= n 1) (return acc))
  (setq acc (* acc n))
  (setq n (- n 1))
  (go top)) ; => 120
```
