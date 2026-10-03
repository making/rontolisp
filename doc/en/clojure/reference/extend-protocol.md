# extend-protocol

`(extend-protocol Protocol Type (method [target & args] body...) ...)`

Adds rows to a protocol's table without redefining the type: one stored lambda per
method per type, like `defmethod` rows (a repeated type's later rows win, like the
oracle). Targets are the kinds `class` answers (`String`, `Number`, `Boolean`,
`Keyword`, `Symbol`, `Character`, `Map`, `Vector`, `Set`, `List`/`Seq`, plus `nil`
and `Object` as the miss default; package-qualified spellings such as `java.lang.String` or
`clojure.lang.IPersistentMap` too) and known record/deftype names; anything else
(an `Instant`, a `Date`, ...) is a named refusal.

```clojure
(defprotocol P (m [x]))
(extend-protocol P
  nil (m [_] :nil)
  String (m [s] :str)
  Object (m [_] :other))
(println (m nil))  ; :nil
(println (m "s"))  ; :str
(println (m 1.5))  ; :other
```
