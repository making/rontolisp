# c84. Clojure: `instance?` of a protocol's interface is an unknown name

Difficulty: Medium

All backends, measured 2026-10-04 against clj 1.12.6:

```clojure
(defprotocol P (m [x]))
(defrecord R [] P (m [_] 1))
(defrecord S [])
(extend-type S P (m [_] 2))
(println (instance? user.P (->R)) (instance? user.P (->S)) (instance? user.P (reify P (m [_] 3))))
; oracle: true false true
; here:   "unknown name: user.P"
```

A protocol defines the interface `ns.P`; a record, deftype or reify implementing it in its
body is an instance, an `extend-type`/`extend-protocol` row is not (`satisfies?` is). The
protocol tables already tell the two apart (`TypeDef.inlineMethods`, the reify's rows under
its fresh tag), so `ClojureDispatchLowering.instanceOf` can resolve a dotted name naming a
known protocol before the `unknown name` refusal and test the body implementations only.
