# geom:make-node

`(geom:make-node &key transform translation rotation rpy axis angle parent)`

A scene-graph node: something that HAS a local transform, so a solid, a camera target and a bare joint frame are all nodes with no slot any of them does not use. `:parent` attaches it on the spot; the remaining keywords build the local transform the way `geom:make-transform` does. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:world-translation (geom:make-node :translation (geom:vec3 0 0 100)))
; => #f(0.0 0.0 100.0)
```
