# load-string

`(load-string s)`

The literal string `s` read and lowered where the call stands, in the current namespace --
definitions declare ahead like a file's -- answering the last form's value (`nil` of an
empty string), like the oracle's. A computed string loads when the call runs on the
interpreter, in the namespace `*ns*` holds; a compiled program carries no lowering, so there
it refuses at run time. As a value one argument.

```clojure
(println (load-string "(+ 1 2)")) ; 3
```
