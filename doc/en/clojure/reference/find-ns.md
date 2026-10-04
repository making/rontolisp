# find-ns

`(find-ns sym)`

`clojure.core/find-ns`: the namespace the symbol names, or `nil` when the program created
none of that name above the call (with `ns` or `in-ns`), required none and `clj -M` loads
none first (`clojure.core`, `clojure.edn`, `clojure.java.io`, `clojure.string`). As a value
a one-argument function.

```clojure
(in-ns 'demo)
(clojure.core/println (clojure.core/str (clojure.core/find-ns 'demo))
                      (clojure.core/find-ns 'no-such))
```

```
demo nil
```
