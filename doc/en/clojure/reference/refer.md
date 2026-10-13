# refer

`(refer ns-sym & filters)`

The namespace `ns-sym` loaded and its vars referred into the current namespace -- bare,
every public one; `:only`, `:exclude` and `:rename` narrow -- answering `nil`, like the
oracle's. Anything computed refuses at run time. As a value any number of arguments (which
refuses).

```clojure
(refer 'clojure.string :only '[upper-case])
(println (upper-case "ab")) ; AB
```
