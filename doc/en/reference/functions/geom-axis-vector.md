# geom:axis-vector

`(geom:axis-vector axis)`

The unit vector an axis designator names: `:x` / `:y` / `:z` / `:-x` / `:-y` / `:-z`, or a vector passed through unchanged. Every rotation entry point takes an axis in this form. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:axis-vector :-y)
; => #f(0.0 -1.0 0.0)
```
