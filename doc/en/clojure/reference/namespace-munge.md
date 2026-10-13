# namespace-munge

`(namespace-munge ns)`

The `str` of `ns` with every dash an underscore, like the oracle's. As a value one
argument.

```clojure
(println (namespace-munge "my.ns-name")) ; my.ns_name
```
