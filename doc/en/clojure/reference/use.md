# use

`(use 'clause ...)`

Loads namespaces and refers the names each quoted clause lists, answering `nil` --
`(:only [...])` narrows them and `(:exclude [...])` subtracts from them,
the same refer wiring `ns` does. `clojure.string`
and `clojure.java.io` (`reader` only) resolve;
an unknown namespace is an error.
An unquoted vector spec is accepted too, though real Clojure rejects it.
A prefix list `'(prefix [sub ...])` wires each member under the prefix, quoted or bare.

```clojure
(use '[clojure.string :only [upper-case]])
(println (upper-case "hi")) ; HI
```
