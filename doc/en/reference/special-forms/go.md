# go

`(go tag)`

Transfers control to the given go tag of the (dynamically) enclosing [`tagbody`](tagbody.md); the forms after the jump point continue executing. It is an error when no enclosing `tagbody` has the tag.

On the JVM and WASM compilers `go` is lexical: it must target a tag of a lexically enclosing `tagbody` in the same function (the interpreter additionally supports dynamic `go` across function boundaries).

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
