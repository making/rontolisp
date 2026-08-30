# geom Package Functions

The `geom` package is solid modeling -- rigid transforms, a scene graph and
boundary-represented solids with a cached triangle mesh -- written in rontolisp
over `linalg` and loaded on first use like it. It reaches for nothing else, so
unlike `objc` / `appkit` it runs on every backend and in the browser playground.
It is **not part of Common Lisp**; reference its names with the `geom:`
qualifier. `geom:transform`, `geom:node`, `geom:solid` and `geom:bounds` are
also CLOS class names, for `typep` and `defmethod` specializers. Each function
below links to its own page; the [solid modeling
guide](../../guides/solid-modeling.md) covers the type model, the winding
convention and the cached mesh.

| Function | Example | Result |
|----------|---------|--------|
| `geom:vec3` | `(geom:vec3 1 2 3)` | a packed single-float 3-vector, the coordinate type of the package |
| `geom:axis-vector` | `(geom:axis-vector :-y)` | the unit vector an axis designator names |
| `geom:axis-angle-matrix` | `(geom:axis-angle-matrix 0.5 :z)` | the 3x3 rotation of that angle about that axis |
| `geom:rpy-matrix` | `(geom:rpy-matrix 0.1 0.2 0.3)` | the 3x3 rotation of a roll, then a pitch, then a yaw |
| `geom:make-transform` | `(geom:make-transform :translation v :rpy '(0 0 1.5))` | a rigid motion, and a value: no parent, no identity, no cache |
| `geom:translation-of` | `(geom:translation-of tf)` | the transform's translation 3-vector |
| `geom:rotation-of` | `(geom:rotation-of tf)` | the transform's 3x3 rotation |
| `geom:compose` | `(geom:compose outer inner)` | a new transform: `inner`'s motion carried into `outer`'s frame |
| `geom:invert` | `(geom:invert tf)` | the inverse rigid motion |
| `geom:transform-point` | `(geom:transform-point tf p)` | the point carried through the transform |
| `geom:inverse-transform-point` | `(geom:inverse-transform-point tf p)` | the point carried back, without building the inverse |
| `geom:make-node` | `(geom:make-node :translation v :parent base)` | a scene-graph node: something that HAS a local transform |
| `geom:local-transform` | `(geom:local-transform n)` | the node's own transform, relative to its parent |
| `geom:world-transform` | `(geom:world-transform n)` | the composed world transform, memoized on the node |
| `geom:world-translation` | `(geom:world-translation n)` | the node's origin in world coordinates |
| `geom:world-rotation` | `(geom:world-rotation n)` | the node's orientation in world coordinates |
| `geom:parent-of` | `(geom:parent-of n)` | the node it is attached to, or `nil` at a root |
| `geom:children-of` | `(geom:children-of n)` | the nodes attached to it, as a list |
| `geom:attach` | `(geom:attach parent child)` | the child, now posed in the parent's frame |
| `geom:detach` | `(geom:detach child)` | the child, out of its parent's frame |
| `geom:translate` | `(geom:translate n v :frame :parent)` | translates the node, accumulating; `:frame` is `:local` (default) or `:parent` |
| `geom:rotate` | `(geom:rotate n 0.5 :z)` | rotates the node, accumulating on its current orientation |
| `geom:place` | `(geom:place n :rpy '(0 0 1.5))` | sets the pose outright, which is what an animation loop wants |
| `geom:reorient` | `(geom:reorient n 0.5 :z)` | sets the rotation, keeping the translation |
| `geom:box` | `(geom:box '(100 200 300))` | a rectangular solid centred on its origin (a scalar gives a cube) |
| `geom:cylinder` | `(geom:cylinder :radius 50 :height 100)` | a cylinder standing on z = 0 |
| `geom:cone` | `(geom:cone :radius 50 :height 120)` | a cone over a ring on z = 0; `:apex` makes it oblique |
| `geom:sphere` | `(geom:sphere :radius 50 :sides 32 :stacks 24)` | a sphere centred on its origin |
| `geom:torus` | `(geom:torus :radius 60 :tube 20)` | a torus in the xy-plane |
| `geom:extrusion` | `(geom:extrusion profile :along 10)` | a closed profile swept along a vector -- the general prism |
| `geom:revolution` | `(geom:revolution profile :sides 64)` | a profile turned about z, capped where it leaves the axis |
| `geom:polyhedron` | `(geom:polyhedron points facets)` | raw points and index loops -- the escape hatch |
| `geom:read-model` | `(geom:read-model "bunny.obj")` | the mesh in a model file, its format sniffed from its bytes |
| `geom:read-obj` | `(geom:read-obj "bunny.obj")` | the mesh in a Wavefront OBJ file |
| `geom:read-stl` | `(geom:read-stl "part.stl")` | the mesh in an STL file, either dialect |
| `geom:read-ply` | `(geom:read-ply "scan.ply")` | the mesh in a PLY file, ASCII or binary little-endian |
| `geom:read-gltf` | `(geom:read-gltf "duck.glb")` | the scene in a glTF 2.0 / GLB file, as a LIST of solids |
| `geom:arrow` | `(geom:arrow :length 200 :radius 6)` | a shaft and a pointed head as one solid, along `:direction` |
| `geom:triad` | `(geom:triad :at (geom:vec3 0 0 0))` | three `geom:arrow`s -- +x red, +y green, +z blue -- as a list |
| `geom:vertices-of` | `(geom:vertices-of s)` | a rank-2 `(n 3)` packed array of MODEL coordinates |
| `geom:facets-of` | `(geom:facets-of s)` | the index loops, each counter-clockwise seen from outside |
| `geom:color-of` | `(geom:color-of s)` | the solid's colour, a 3-vector of 0..1 components; `setf`-able |
| `geom:label-of` | `(geom:label-of s)` | whatever the caller passed as `:label`; `setf`-able |
| `geom:user-data` | `(geom:user-data s)` | a slot a consumer hangs its own state on; `setf`-able |
| `geom:scale` | `(geom:scale s 2)` | a NEW solid with scaled model coordinates; the operand is untouched |
| `geom:nscale` | `(geom:nscale s 2)` | scales in place, dropping both caches and `geom:user-data` |
| `geom:mesh` | `(geom:mesh s)` | the model-space triangles, 18 floats each, computed once and cached |
| `geom:wireframe` | `(geom:wireframe s)` | each edge once, 6 floats a segment, likewise cached |
| `geom:mesh-triangle-count` | `(geom:mesh-triangle-count s)` | how many triangles the mesh holds |
| `geom:bounds` | `(geom:bounds (list a b))` | the world-coordinate bounding box of a solid or a list of them |
| `geom:lower-of` | `(geom:lower-of b)` | the minimum corner of a bounding box |
| `geom:upper-of` | `(geom:upper-of b)` | the maximum corner of a bounding box |
| `geom:bounds-center` | `(geom:bounds-center b)` | its midpoint -- what a viewer points its camera at |
| `geom:bounds-extent` | `(geom:bounds-extent b)` | its size along each axis |
| `geom:bounds-union` | `(geom:bounds-union a b)` | the smallest box containing both |
| `geom:volume` | `(geom:volume s)` | the volume by the divergence theorem -- also a winding check |
| `geom:centroid` | `(geom:centroid s)` | the centre of volume, in model coordinates |
| `geom:surface-area` | `(geom:surface-area s)` | the total area of the mesh triangles |
| `geom:union` | `(geom:union a b)` | a new solid covering everything either operand covers |
| `geom:difference` | `(geom:difference a b)` | a new solid: `a` with `b` removed |
| `geom:intersection` | `(geom:intersection a b)` | a new solid covering only what both operands cover |
| `geom:section` | `(geom:section s :normal :z)` | the cross-section loops where a plane cuts the solid |
| `geom:history` | `(geom:history s)` | what built the solid: `nil`, or `(op a b)` for a boolean result |

