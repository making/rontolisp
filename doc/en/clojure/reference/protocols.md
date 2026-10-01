# Protocols, records and types

A protocol is a method table plus one dispatcher per method over the target's tag;
a record is a map with a type tag over the same `equal` table every map uses. The
whole story runs on all four backends with no per-backend value shape.

| Name | Example | Result |
|---|---|---|
| `defprotocol` | `(do (defprotocol P13 (m [x])) (satisfies? P13 nil))` | `false` |
| `defrecord` | `(do (defrecord R13 [a]) (get (->R13 1) :a))` | `1` |
| `deftype` | `(do (deftype T13 [a]) (instance? T13 (T13. 2)))` | `true` |
| `reify` | `(do (defprotocol Q13 (m [x])) (m (reify Q13 (m [_] 7))))` | `7` |
| `extend-protocol` | `(do (defprotocol E13 (m [x])) (extend-protocol E13 String (m [s] :s)) (m "x"))` | `:s` |
| `extend-type` | `(do (defprotocol Y13 (m [x])) (extend-type String Y13 (m [s] :s)) (m "x"))` | `:s` |
| `extend` | `(do (defprotocol X13 (m [x])) (extend String X13 {:m (fn [s] :f)}) (m "x"))` | `:f` |
| `satisfies?` | `(do (defprotocol S13 (m [x])) (satisfies? S13 1))` | `false` |
