# go

`(go tag)`

Transfers control to the given go tag of the (dynamically) enclosing [`tagbody`](tagbody.md); the forms after the jump point continue executing. It is an error when no enclosing `tagbody` has the tag.

On the JVM and WASM compilers `go` is lexical: it must target a tag of a `tagbody` that lexically encloses it (the interpreter additionally supports dynamic `go` across function-call boundaries, i.e. a tag established by the *caller*). A tag reached from inside a nested `lambda` -- what a [`handler-bind`](../macros/handler-bind.md) handler that resumes its loop with a `go` produces -- is lowered to a non-local exit that re-enters the `tagbody` at the tag, so it works on every backend; such a program compiles in exception-handling mode.

```lisp
(let ((acc nil))
  (tagbody
    (push :a acc)
    (go skip)
    (push :never acc)
   skip
    (push :b acc))
  (nreverse acc)) ; => (:A :B)
```
