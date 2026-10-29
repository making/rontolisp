# tap>

`(tap> x)`

`clojure.core/tap>`: hands `x` (`nil` too) to every tap [add-tap](add-tap.md) added, answering `true`. On the interpreter and the JVM the value joins the oracle's queue of 1024 values, which a tap thread drains, so a full queue answers `false`. On the WASM backends, single-threaded by construction, the taps are called before `tap>` returns. A tap's failure is ignored. As a value a one-argument function.

```clojure
(def seen (atom []))
(add-tap #(swap! seen conj %))
(println (tap> :t)) ; true
(loop [] (when (empty? (locking seen @seen)) (recur)))
(println @seen) ; [:t]
```
