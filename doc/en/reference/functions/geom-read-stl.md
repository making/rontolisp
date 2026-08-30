# geom:read-stl

`(geom:read-stl path &key color label)`

The mesh in an STL file, as a [`geom:solid`](geom-polyhedron.md). Both dialects are read and **which one a file is written in is decided from the file's own shape**, never from its extension and never from its first word: a binary writer putting `solid <name>` in its 80-byte header is routine. An ASCII file opens with the token `solid` and carries `facet` or `endsolid` on its next line; anything else is binary.

STL is a triangle soup with no index table, so the solid carries **three vertices per facet** -- 8,954 triangles come back as 26,862 vertices. The per-facet normal the file stores is ignored: `geom` computes Newell's from the geometry, and half the writers in the world store zeros there.

```console
CL-USER> (geom:read-stl "part.stl" :color (geom:vec3 0.45 0.7 0.92))
#<GEOM:SOLID 26862 vertices 8954 facets>
CL-USER> (geom:volume *)
16.084489098307944
```

A binary file's twelve float32s a triangle are moved by one [`read-sequence`](read-sequence.md) over a packed single-float array, so reading one costs no per-number arithmetic at all; an ASCII file is scanned character by character like [`geom:read-obj`](geom-read-obj.md).

## Backend support

Every backend that has a filesystem: the interpreter, a compiled `.class`/`.jar`, WASM Preview 1 and the WASI 0.3 component, with the same answer on all four. The dialect test reads the file's shape rather than its length precisely so that it can: [`file-length`](file-length.md) answers `nil` on both WASM backends by design.
