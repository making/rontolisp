# deftype

`(deftype Name [fields...] Protocol (method [target & args] body...) ...)`

Defines a deftype: a record-shaped value opaque to the map verbs. The value shares
the record's 4-list shape with a `:C%TYPE` tag; reads miss (`get` answers the
default), writers and `seq`/`count`/`empty?` signal, and `=` is identity, like the
oracle. Only the positional constructor `->Name` lowers (the oracle defines no
`map->Name` for deftypes); `(Name. ...)` rewrites to it. Inline method bodies see
the fields as locals, like `defrecord`. The name joins the whole-file pre-scan.

```clojure
(deftype T [a])
(def t (T. 1))
(println (= t t))          ; true
(println (= t (T. 1)))     ; false
(println (get t :a))       ; nil
(println (instance? T t))  ; true
```
