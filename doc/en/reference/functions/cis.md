# cis

`(cis number)`

Returns the point on the unit circle at the angle `number` radians: `(cis x)` is `e^{ix}`, that is `(cos x, sin x)`. The result is always a complex number, even for a real argument. A complex argument decays toward the origin: `cis` of `re + i·im` is `(e^{-im} cos re, e^{-im} sin re)`. Every backend computes it from fdlibm's `exp`/`cos`/`sin`, so the digits agree everywhere. `(cis 0)` is exactly `#C(1.0 0.0)` on every backend.

```lisp
(cis 0) ; => #C(1.0 0.0)
```
