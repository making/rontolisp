# meta

`(meta obj)`

Answers the metadata map [with-meta](with-meta.md) (or reader metadata on a collection
literal) attached to `obj`, or `nil` when it carries none. A [var](var.md) answers what
its newest definition recorded (`:doc`, `:arglists`, `:line`, ...).

```clojure
(println (meta (with-meta {:a 1} {:source :db}))) ; {:source :db}
(println (meta ^:flag [1]))                      ; {:flag true}
```
