# deftype

`(deftype Name [fields...] Protocol (method [target & args] body...) ...)`

Defines a deftype: a record-shaped value opaque to the map verbs. The value shares
the record's shape, class name included, with a `:C%TYPE` tag; reads miss (`get` answers the
default), writers and `seq`/`count`/`empty?` signal, and `=` is identity, like the
oracle, unless the body implements the interface the function reads (below). Only the positional constructor `->Name` lowers (the oracle defines no
`map->Name` for deftypes); `(Name. ...)` rewrites to it. Inline method bodies see
the fields as locals, like `defrecord`. A field marked `^:unsynchronized-mutable` or
`^:volatile-mutable` is private to those methods, which assign it with
[`set!`](set-bang.md). An instance call reaches the inline methods and the immutable
fields ([`.name`](dot-name.md)). The name joins the whole-file pre-scan.

```clojure
(deftype T [a])
(def t (T. 1))
(println (= t t))          ; true
(println (= t (T. 1)))     ; false
(println (get t :a))       ; nil
(println (instance? T t))  ; true
```

The body may implement the `clojure.lang` interfaces the core functions consult and
override `Object`'s methods, like [reify](reify.md#host-interfaces)'s, its methods seeing
the fields: a deftype of `Counted` counts, one of `IDeref` derefs, one of `IObj` carries the
metadata its own methods keep.

```clojure
(deftype Box [v] clojure.lang.IDeref (deref [_] v))
@(Box. 5) ; => 5
(deftype Squares [n]
  clojure.lang.Indexed
  (nth [_ i] (* i i))
  (nth [_ i nf] (if (< -1 i n) (* i i) nf))
  (count [_] n))
(count (Squares. 4)) ; => 4
(nth (Squares. 4) 9 :none) ; => :none
(deftype Money [cents] Object (toString [_] (str cents " cents")))
(str (Money. 5)) ; => "5 cents"
```
