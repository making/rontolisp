# promise

`(promise)`

`clojure.core/promise`: a promise, undelivered: `deref` (and `@`) blocks until [deliver](deliver.md) delivers it. `future?` is `false` of it, `realized?` whether it was delivered, and the timed `deref` answers its default while nothing was delivered. On the WASM backends, single-threaded by construction, the call refuses instead. As a value a zero-argument function.

```clojure
(let [p (promise)] (deliver p 7) (println @p (realized? p))) ; 7 true
```
