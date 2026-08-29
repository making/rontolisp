# geom:triad

`(geom:triad &key length radius head-radius head-length sides at)`

The three arrows a coordinate frame is drawn as -- +x red, +y green, +z blue, labelled `"x"` / `"y"` / `"z"` -- as a LIST of [`geom:arrow`](geom-arrow.md) solids the caller owns, not a viewer mode. `:at` places all three, so `(geom:triad :at (geom:vec3 0 0 0))` is the origin indicator; add them like any other solid with `(dolist (a (geom:triad)) (scene:add *v* a))`. The default length is the one `scene:axes`' line triad draws at the viewer's default camera distance. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(mapcar #'geom:label-of (geom:triad :at (geom:vec3 0 0 0)))
; => ("x" "y" "z")
```
