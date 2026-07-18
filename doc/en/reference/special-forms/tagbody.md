# tagbody

`(tagbody {tag | form}...)`

Evaluates its body forms in order for effect. A bare symbol (or integer) in the body is a *go tag*: [`go`](go.md) transfers control to the form after that tag, forward or backward, so loops and state machines can be written with explicit jumps. Falling off the end returns nil.

On the JVM and WASM compilers `go` is lexical: it must target a tag of a lexically enclosing `tagbody` in the same function (the interpreter additionally supports dynamic `go` across function boundaries).

```lisp
(let ((n 0))
  (tagbody
   top
    (incf n)
    (when (< n 5) (go top)))
  n) ; => 5
```
