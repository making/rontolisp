# ns-unalias

`(ns-unalias ns alias-sym)`

The alias `alias-sym` in the namespace `ns` forgotten where the program lowers, answering
`nil`, like the oracle's. Anything computed answers when the call runs on the interpreter, which keeps the program's lowering, and refuses at run time in a compiled program. As a value two
arguments.

```clojure
(alias 'doc-eg-s3 'clojure.string)
(ns-unalias 'user 'doc-eg-s3)
(println (get (ns-aliases 'user) 'doc-eg-s3)) ; nil
```
