# remove-ns

`(remove-ns sym)`

The namespace `sym` names taken out of the table -- `find-ns` misses it and `all-ns`
skips it after -- `nil` for one never made, like the oracle's. Anything but a symbol is
refused. As a value one argument.

```clojure
(create-ns 'doc-eg-gone)
(remove-ns 'doc-eg-gone)
(println (some? (find-ns 'doc-eg-gone))) ; false
```
