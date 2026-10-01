# defmethod

`(defmethod name dispatch-value [params...] body...)`

Stores a method lambda in the multimethod's table under `dispatch-value`; the parameters
destructure, map patterns included. On a call, an exact hit against the dispatch value applies
directly. Otherwise the dispatcher searches every method the value descends from through the
multimethod's hierarchy: the strictly most specific wins, `prefer-method` breaks the remaining
ties, and an unbroken tie signals `Multiple methods ...`.

Dispatch values compare like `equal` table keys -- vectors by identity, so a vector dispatch
value only hits itself.

Deviation: vector dispatch values compare by identity, not structurally.

```clojure
(defmulti m :shape)
(defmethod m :circle [x] 1)
(defmethod m :square [x] 2)
(println (m {:shape :circle})) ; 1
```
