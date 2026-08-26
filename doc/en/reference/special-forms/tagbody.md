# tagbody

`(tagbody {tag | form}...)`

Evaluates its body forms in order for effect. A bare symbol (or integer) in the body is a *go tag*: [`go`](go.md) transfers control to the form after that tag, forward or backward, so loops and state machines can be written with explicit jumps. Falling off the end returns nil.

On the JVM and WASM compilers `go` is lexical: it must target a tag of a `tagbody` that lexically encloses it (the interpreter additionally supports dynamic `go` across function-call boundaries, i.e. a tag established by the *caller*). A tag reached from inside a nested `lambda` -- what a [`handler-bind`](../macros/handler-bind.md) handler that resumes its loop with a `go` produces -- is lowered to a non-local exit that re-enters the `tagbody` at the tag, so it works on every backend; such a program compiles in exception-handling mode.

```lisp
(let ((n 0))
  (tagbody
   top
    (incf n)
    (when (< n 5) (go top)))
  n) ; => 5
```
