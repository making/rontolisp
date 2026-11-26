# geom:read-ply

`(geom:read-ply path &key color label)`

The mesh in a PLY (Stanford polygon format) file, as a [`geom:solid`](geom-polyhedron.md). Both `ascii` and `binary_little_endian` bodies are read; `binary_big_endian` is refused by name -- the bulk binary path is little-endian by contract, and mis-reading every float would be strictly worse than saying so.

The header describes the body -- every element, its count, and every property with its type -- so `x`, `y` and `z` are taken from wherever the vertex element put them, and every other property is **read past by its declared width** rather than guessed at: the Stanford bunny's `confidence` and `intensity` columns, a scanner's per-vertex colours, a per-face colour after the index list. A `geom:solid` has one colour, so per-vertex and per-face colours are dropped, not averaged. A file with no `face` element -- a raw range scan -- answers its vertices with no facets rather than an error.

```console
CL-USER> (geom:read-ply "bun_zipper.ply" :color (geom:vec3 0.85 0.72 0.5))
#<GEOM:SOLID 35947 vertices 69451 facets>
CL-USER> (geom:volume *)
7.700565237386743e-4
```

A binary body whose vertex properties are all float32 is moved by one [`read-sequence`](read-sequence.md) over a packed single-float array -- the whole vertex block in a single native transfer; interleaved colours make the stride non-uniform and cost one extra read per vertex. Faces are two transfers each (one count, one bulk index read), the same shape as [`geom:read-stl`](geom-read-stl.md)'s two a triangle. An ASCII body is scanned character by character like [`geom:read-obj`](geom-read-obj.md).

## Backend support

Every backend that has a filesystem: the interpreter, a compiled `.class`/`.jar`, WASM Preview 1 and the WASI 0.3 component, with the same answer on all four.
