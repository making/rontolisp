# refer-clojure

`(refer-clojure & filters)`

Applies the `(:refer-clojure ...)` filter -- `:only`, `:exclude`, `:rename` -- to the
current namespace, answering `nil`, like the [`ns`](ns.md) clause spelled as a
standalone form. A renamed core var answers under its new name only (the old name is
no longer referred). As a value it is unknown (`(def x refer-clojure)` refuses).

```clojure
(refer-clojure :rename '{map core-map})
(println (core-map inc [1 2])) ; (2 3)
```
