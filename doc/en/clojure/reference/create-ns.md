# create-ns

`(create-ns sym)`

The namespace `sym` names, made when missing (a removed one made again), like the
oracle's. Anything but a symbol is refused. As a value one argument.

```clojure
(println (ns-name (create-ns 'doc-eg-ns))) ; doc-eg-ns
```
