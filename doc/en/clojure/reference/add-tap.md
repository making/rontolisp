# add-tap

`(add-tap f)`

`clojure.core/add-tap`: adds `f` to the taps [tap>](tap-gt.md) calls, answering `nil`; adding a tap twice keeps one. Taps are called in the order they were added, on the tap thread on the interpreter and the JVM and at the `tap>` call on the WASM backends. As a value a one-argument function.

```clojure
(def seen (atom []))
(def record #(swap! seen conj %))
(println (add-tap record) (add-tap record)) ; nil nil
(tap> 1)
(loop [] (when (empty? (locking seen @seen)) (recur)))
(println @seen) ; [1]
```
