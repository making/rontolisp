# reset-meta!

`(reset-meta! ref m)`

Replaces the metadata of the reference `ref` -- a var, an atom, a ref, an agent or a
namespace -- with the map `m` (`nil` for none) and answers `m`. The refusals are
`alter-meta!`'s. As a value a two-argument function.

```clojure
(def a (atom 0))
(reset-meta! a {:owner "me"})
(println (meta a)) ; {:owner me}
```
