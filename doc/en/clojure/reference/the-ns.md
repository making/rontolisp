# the-ns

`(the-ns x)`

`clojure.core/the-ns`: the namespace `x` names -- a symbol naming one the program created
(with `ns` or `in-ns`, above the call), one it required, or `clojure.core` -- or `x` itself
when it is a namespace. Any other symbol signals `No namespace: x found`, like the oracle.
A namespace prints as `#object[clojure.lang.Namespace "name"]` and `str` answers its name.
As a value a one-argument function.

```clojure
(println (str (the-ns 'user)) (= *ns* (the-ns 'user)) (identical? *ns* (the-ns *ns*)))
(println (try (the-ns 'no-such) (catch Exception e (ex-message e))))
```

```
user true true
No namespace: no-such found
```
