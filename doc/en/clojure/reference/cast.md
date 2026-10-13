# cast

`(cast class x)`

`x` when it is an instance of `class`, `nil` of `nil`, else the oracle's
`ClassCastException` (`Cannot cast A to B`). A `nil` class is its
`NullPointerException`, anything but a class its `ClassCastException`. Of a
class the call names the test is the `instance?` one, so records, deftypes and
the core kinds cast on every backend; a host class no value here has always
refuses (host classes exist on no wasm backend). A computed class casts by the
protocol tag, what `class` answers (so `(cast (class y) x)` holds), a computed
host class by the kind its name has here. As a value two arguments.

```clojure
(println (cast String "a")) ; a
(println (cast String nil)) ; nil
(println (try (cast Integer "a") (catch ClassCastException e :cce))) ; :cce
```
