# Clojure: `defprotocol` with no methods (a marker protocol) is refused

Difficulty: Low

```clojure
(defprotocol M) (extend-type String M) (println (satisfies? M "a"))
;; clj, cljw 1.15.4: true   rontolisp: defprotocol takes at least one method: M
```

`ClojureProtocolLowering` asserts a non-empty method list. Marker protocols are legal and
used by real libraries: core.async's `clojure.core.async.impl.protocols/UnblockingBuffer`
stops core.async 1.6.681 at load time (`.todo/g87`). Allow zero methods; `satisfies?`,
`extends?`, `extend-type`/`extend-protocol`/`reify`/`deftype` naming it must work on all
four backends.
