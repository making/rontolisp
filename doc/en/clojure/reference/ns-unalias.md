# ns-unalias

`(ns-unalias ns alias-sym)`

The alias `alias-sym` in the namespace `ns` forgotten where the program lowers, answering
`nil`, like the oracle's. Anything computed refuses at run time. As a value two arguments.

```clojure
(alias 'doc-eg-s3 'clojure.string)
(ns-unalias 'user 'doc-eg-s3)
(println (get (ns-aliases 'user) 'doc-eg-s3)) ; nil
```
