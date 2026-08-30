# geom:read-model

`(geom:read-model path &key format color label)`

The mesh in a model file, as a [`geom:solid`](geom-polyhedron.md) -- the one entry point for a file whose format the program does not know, such as a viewer opening whatever it was handed. The format is decided from the file's own bytes; `:format` (`:obj`, `:stl`) says it outright and skips the sniffing. Naming the format instead -- [`geom:read-obj`](geom-read-obj.md), [`geom:read-stl`](geom-read-stl.md) -- is the smaller artifact: this one can reach every reader, so it carries every reader.

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

PLY and glTF are recognized but not read, so a file this build cannot handle is refused by name rather than parsed sideways:

```console
CL-USER> (geom:read-model "dragon.ply")
; Error: geom:read-model: dragon.ply is PLY, which this build does not read yet
```

## Backend support

Every backend that has a filesystem: the interpreter, a compiled `.class`/`.jar`, WASM Preview 1 and the WASI 0.3 component, with the same answer on all four. The browser playground has no filesystem, and a program that reads no model file does not carry the readers at all -- they are pruned out of its compiled output.
