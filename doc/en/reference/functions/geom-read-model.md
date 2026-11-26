# geom:read-model

`(geom:read-model path &key format color label)`

The mesh in a model file, as a [`geom:solid`](geom-polyhedron.md) -- the one entry point for a file whose format the program does not know, such as a viewer opening whatever it was handed. The format is decided from the file's own bytes; `:format` (`:obj`, `:stl`, `:ply`, `:gltf`, `:glb`) says it outright and skips the sniffing. Naming the format instead -- [`geom:read-obj`](geom-read-obj.md), [`geom:read-stl`](geom-read-stl.md), [`geom:read-ply`](geom-read-ply.md), [`geom:read-gltf`](geom-read-gltf.md) -- is the smaller artifact: this one can reach every reader, so it carries every reader. A glTF answers a **list** of solids, as [`geom:read-gltf`](geom-read-gltf.md) documents; every other format answers one solid.

`:color` and `:label` are the solid's, exactly as on every constructor in the package. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (geom:read-model "bunny.obj" :color (geom:vec3 0.85 0.72 0.5))
#<GEOM:SOLID 35947 vertices 69451 facets>
CL-USER> (geom:read-model "part.stl" :label "bracket")
#<GEOM:SOLID "bracket" 26862 vertices 8954 facets>
```

## How the format is decided

Content first, and the name only where no content test can answer:

| Test | Answer |
|---|---|
| the bytes open with `glTF` | glTF-Binary |
| the bytes open with `ply` and a line break | PLY |
| the first token is `solid` | STL (ASCII) |
| the first token is `v`, `vn`, `vt`, `f`, `g`, `o`, `s`, `usemtl` or `mtllib` | OBJ |
| the first token opens with `{` | glTF (JSON) |
| otherwise | the file's extension |

A **binary STL has no magic number at all** -- a wart of the format -- so a binary `.stl` whose 80-byte header is not the word `solid` is named by its extension. What the extension never decides is the ASCII/binary split: both dialects are `.stl`, and `geom:read-stl` settles that from the bytes.

A file no test recognizes is refused naming the file rather than parsed sideways, and what a reader itself cannot carry -- a `binary_big_endian` PLY, a compressed or skinned glTF -- is refused by name on that reader's own page:

```console
CL-USER> (geom:read-model "mystery.dat")
; Error: geom:read-model: cannot tell what format mystery.dat is; pass :format
```

## Backend support

Every backend that has a filesystem: the interpreter, a compiled `.class`/`.jar`, WASM Preview 1 and the WASI 0.3 component, with the same answer on all four. The browser playground has no filesystem, and a program that reads no model file does not carry the readers at all -- they are pruned out of its compiled output.
