# go

`(go tag)`

Transfers control to the given go tag of the (dynamically) enclosing [`tagbody`](tagbody.md); the forms after the jump point continue executing. It is an error when no enclosing `tagbody` has the tag.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(let ((acc nil))
  (tagbody
    (push :a acc)
    (go skip)
    (push :never acc)
   skip
    (push :b acc))
  (nreverse acc)) ; => (:a :b)
```
