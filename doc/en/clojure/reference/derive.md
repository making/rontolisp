# derive

`(derive tag parent)`
`(derive h tag parent)`

Records `tag` as a child of `parent`. The two-argument form rewrites the global hierarchy and
answers `nil`; the three-argument form answers an updated hierarchy value, leaving the argument
untouched -- `defmulti`'s `:hierarchy` dispatches through such a value.

```clojure
(derive :circle :shape)
(println (isa? :circle :shape)) ; true
(def h (derive (make-hierarchy) :p :q))
(println (isa? h :p :q)) ; true
(derive ::savings ::account)
(println (isa? ::savings ::account)) ; true
```
