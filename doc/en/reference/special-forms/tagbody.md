# tagbody

`(tagbody {tag | form}...)`

Evaluates its body forms in order for effect. A bare symbol (or integer) in the body is a *go tag*: [`go`](go.md) transfers control to the form after that tag, forward or backward, so loops and state machines can be written with explicit jumps. Falling off the end returns nil.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(let ((n 0))
  (tagbody
   top
    (incf n)
    (when (< n 5) (go top)))
  n) ; => 5
```
