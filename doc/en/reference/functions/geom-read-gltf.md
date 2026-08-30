# geom:read-gltf

`(geom:read-gltf path &key color label)`

The scene in a glTF 2.0 file, as a **list of [`geom:solid`](geom-polyhedron.md)s** -- one per mesh primitive, each coloured by its material's `baseColorFactor` (`:color` overrides them all). Both carriers are read: a `.glb` (JSON chunk + BIN chunk) and a `.gltf` whose buffers are `.bin` files beside it or base64 `data:` URIs. A remote (`http:`) buffer URI is refused -- this is a file reader, not a fetcher.

A glTF is a scene, not a mesh, and the node hierarchy survives the read: each glTF node becomes a [`geom:node`](geom-make-node.md) posed by its translation/rotation/matrix, every solid is attached under its node, and the answer is the flat list -- so [`scene:add`](scene-add.md) splices it, [`geom:bounds`](geom-bounds.md) measures it as one, and each solid's [`geom:world-transform`](geom-world-transform.md) carries its parents. A multi-part model's parts land where its nodes say.

**A node's scale is baked into its primitives' vertices**, accumulated down the tree with each child's translation scaled by the product above it -- [`geom:transform`](geom-make-transform.md) is rigid by decision, so a 2x-scaled node's cube *measures* 8x the volume rather than carrying a scale the measurements cannot see. That composition is exact for uniform scales; a non-uniform scale above a rotated child would shear, which no rigid transform can represent, and is refused by name.

```console
CL-USER> (geom:read-gltf "Duck.glb")
(#<GEOM:SOLID "LOD3spShape" 2399 vertices 4212 facets>)
CL-USER> (geom:volume (first *))
1.1957991851442398
```

The JSON goes through [`rontolisp:json-parse`](rontolisp-json-parse.md); the buffers through [`read-sequence`](read-sequence.md) over packed arrays, which is exactly what a bufferView is -- POSITION is one native transfer, indices another. Only a base64 `data:` URI is decoded by Lisp arithmetic, float32 assembly included, so an embedded multi-megabyte buffer is the slow path.

## What is refused by name

A silent partial read of a glTF is worse than a refusal, so each of these names itself: a primitive `mode` other than triangles, sparse accessors, any `extensionsRequired` entry (Draco and meshopt compression arrive this way), skins, animations, and glTF 1.x. Textures, per-vertex normals and texture coordinates are read past -- a solid has one colour and computes its own normals.

## Backend support

Every backend that has a filesystem: the interpreter, a compiled `.class`/`.jar`, WASM Preview 1 and the WASI 0.3 component, with the same answer on all four.
