# alias

`(alias alias-sym ns-sym)`

The alias `alias-sym` for the namespace `ns-sym` wired into the current namespace for the
forms below -- so `alias/var` and `::alias/kw` resolve -- answering `nil`, like the
oracle's. Anything computed refuses at run time. As a value two arguments.

```clojure
(alias 'doc-eg-s2 'clojure.string)
(println (doc-eg-s2/join "-" ["a"])) ; a-b
```
