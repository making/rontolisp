# geom:read-obj

`(geom:read-obj path &key color label)`

The mesh in a Wavefront OBJ file, as a [`geom:solid`](geom-polyhedron.md). `v` lines are the vertices (a fourth `w` component is ignored) and `f` lines the facets, whose tokens may be `v`, `v/vt`, `v/vt/vn` or `v//vn`; an index is 1-based when positive and counts back from the vertices seen so far when negative. A facet may have any number of vertices -- OBJ quads stay quads, and [`geom:mesh`](geom-mesh.md) fan-triangulates them like every other facet.

Every other record is read past: `vn`, `vt`, `g`, `o`, `s`, `usemtl`, `mtllib` and comments. **A file naming several objects reads as ONE solid**, which is what a solid is: a single boundary representation with one colour. Materials, texture coordinates and per-vertex normals are not kept -- `geom` has no slot for any of them.

```console
CL-USER> (geom:read-obj "bunny.obj" :label "bunny")
#<GEOM:SOLID "bunny" 35947 vertices 69451 facets>
CL-USER> (geom:volume *)
7.700565237493941e-4
```

An OBJ carries its own units -- that bunny is 0.2 across, in metres -- so `(scene:fit v)` is what frames it and `(scene:grid v :extent nil)` is usually what you want beside it. A face wound clockwise seen from outside makes `geom:volume` subtract instead of add, so a negative volume means the file's winding is inverted.

## Backend support

Every backend that has a filesystem: the interpreter, a compiled `.class`/`.jar`, WASM Preview 1 and the WASI 0.3 component, with the same answer on all four. Numbers are scanned character by character rather than through the reader, so exponent notation (`1.30e-2`) reads as a float everywhere -- [`read-from-string`](read-from-string.md) answers a symbol for that on the WASM backends.
