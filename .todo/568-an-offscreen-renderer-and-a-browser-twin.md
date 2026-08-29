# An offscreen renderer, and a browser twin for `geom`

Difficulty: Medium

Member 5 of `563-solid-modeling-and-a-3d-viewer.md`; depends on
`564-the-geom-package-transforms-scene-graph-and-solids.md` and, for its first half,
`565-the-scene-package-a-3d-viewer-over-metal.md`.

## The two problems this solves at once

**No test may open a window.** That is the standing rule for everything built on `objc:`
(`.kb/objc.md`), and it is why `examples/macos/counter.lisp` and the Metal examples are
verified by hand. As a result `565`'s renderer -- a camera, a projection, a per-solid model
matrix, a winding convention, a depth test -- ships with no automated coverage at all,
while the arithmetic inside it is exactly the kind that breaks silently and is caught by
looking at four pixels.

**`geom` reaches further than its renderer does.** `564` ships a modeling package that runs
identically on all four backends, including the browser playground (`.kb/wit.md`), and then
the only thing that can DRAW its solids is macOS-only. A user in the playground can build a
lattice and measure it but not see it.

## Half one: render with no display

`.kb/objc.md` already names the technique and the project has used it before: render into
an offscreen `MTLTexture` (shared storage, render-target usage), read it back with
`getBytes:bytesPerRow:fromRegion:mipmapLevel:` into an `objc:data` block and inspect the
pixels with `objc:bytes` -- which is how the triangle and cube examples were verified. Give
`scene` an offscreen mode over the same render path (not a second one, or the test covers
the wrong code), then assert things a picture makes obvious and a number does not:

- a red box at the origin puts red pixels at the centre of the frame and background at the
  corners;
- a solid moved behind another is occluded -- the depth attachment works;
- a solid whose facets are wound inside out does NOT appear -- back-face culling and the
  winding convention agree with `564`'s `volume` oracle;
- `fit` puts the whole bounding box inside the frame, from several camera angles;
- the same scene renders the same bytes twice (determinism, the way
  `.kb/emitted-output-determinism.md` treats emitted code).

Gate the test on macOS, the way `ObjcNativeImageForeignConfigTest` and the `examples.yaml`
`os: [mac]` rows already are. A PNG writer is optional and probably worth it anyway: a
failing render is far easier to diagnose as a file than as a byte count.

## Half two: the browser twin

`examples/browser/webgl-*` is the existing pattern, and `565`'s design ports to WebGL
almost directly -- it was chosen partly for that: a per-solid vertex buffer uploaded once,
a per-draw model matrix uniform, one draw call a solid. The differences to expect are the
ones `.kb/objc.md` already records from the other direction (Metal's clip space puts z in
[0, 1] and GL's in [-1, 1]; WebGL renames a buffer behind your back where Metal makes you
rotate copies -- the second is in the twin's favour).

Decide the shape before writing it: a `webgl-common/` helper plus an example is the
established one, and it is probably right here. What must NOT happen is a second modeling
layer -- the twin consumes `geom:mesh` and `geom:world-transform` unchanged, or the two
renderers will drift and `geom` will grow a browser dialect.

## Do

1. The offscreen path in `scene`, sharing `565`'s render function.
2. The pixel assertions above, as a real test class, macOS-gated.
3. The WebGL example under `examples/browser/`, with an `examples.yaml` row if it can be
   driven headlessly the way the existing WebGL demos are.
4. `.kb/geom.md` gains a "how this is tested" section -- currently it would have none --
   and the note that the renderer's design is what makes the twin possible.

## Out of scope

A software rasterizer in Lisp. It would remove the macOS gate entirely and is a tempting
third option, but it is a much larger item than either half above and it would test a
renderer nobody ships. If someone wants it later, the argument to make is portability of
the TEST, not of the product.
