# load-string

`(load-string s)`

The literal string `s` read and lowered where the call stands, in the current namespace --
definitions declare ahead like a file's -- answering the last form's value (`nil` of an
empty string), like the oracle's. Anything computed refuses at run time (no compiler runs
there). As a value one argument (which refuses).

```clojure
(println (load-string "(+ 1 2)")) ; 3
```
