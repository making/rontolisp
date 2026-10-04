# ns-name

`(ns-name ns)`

`clojure.core/ns-name`: the name of the namespace `ns` (or of the one a symbol names, as
[the-ns](the-ns.md) finds it) as a symbol. As a value a one-argument function.

```clojure
(println (ns-name *ns*) (symbol? (ns-name *ns*)) (map ns-name ['user]))
```

```
user true (user)
```
