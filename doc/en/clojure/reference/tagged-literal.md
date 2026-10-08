# tagged-literal

`(tagged-literal tag form)`

`clojure.core/tagged-literal`: the tagged literal of the symbol `tag` over `form`, what a
tag reads as inside a reader conditional under `{:read-cond :preserve}` (a record
literal's, `#inst`'s and `#uuid`'s too; outside one a tag reads through the
[data readers](../syntax.md#tagged-literals) or into an `#inst` or `#uuid`
[value](instants.md), and with `*default-data-reader-fn*` bound to `tagged-literal`
`read-string` reads any other tag as one). It
looks up `:tag` and `:form` like a map (any other key answers the default), is `=` to
another of the same tag and an `=` form, and prints as `#tag form`; it is no collection and
no function. `tag` must be a symbol or `nil` (anything else is a `ClassCastException`).
[tagged-literal?](tagged-literal-p.md) tests one. Runs on every backend; as a value, two
arguments.

```clojure
(def rc (read-string {:read-cond :preserve} "#?(:cljs #js {:a 1} :clj 2)"))
(def t (second (:form rc)))
(println t (:tag t) (:form t))
(println (= t (tagged-literal 'js {:a 1})))
```

```
#js {:a 1} js {:a 1}
true
```
