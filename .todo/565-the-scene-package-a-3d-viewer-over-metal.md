# The `metal` and `scene` packages: a shipped Metal surface, and a 3D viewer over it

Difficulty: Medium

Member 2 of `563-solid-modeling-and-a-3d-viewer.md`; depends on
`564-the-geom-package-transforms-scene-graph-and-solids.md`. Read the parent and
`563-solid-modeling-and-a-3d-viewer/README.md` first -- `scene.lisp` there is a working
prototype of the whole surface below, verified under `java -jar` and as a compiled JVM
class (`shot.png`, `shot-jvm.png`).

## What ships

A window that shows `geom` solids and lets you move around them:

```lisp
(defvar *v* (scene:viewer :title "..." :width 900 :height 640))
(scene:add *v* solid ...)   (scene:drop *v* s)   (scene:clear *v*)   (scene:contents *v*)
(scene:fit *v*)                                   ; frame everything
(scene:camera *v* :azimuth a :elevation e :distance d :target v)
(scene:grid *v* :extent 600 :spacing 50)          (scene:grid-color *v* rgb)
(scene:background *v* rgba)                       (scene:shading *v* :solid|:wireframe|:both)
(scene:refresh *v*)                               ; one frame
(scene:animate *v* fn)                            ; fn per frame, then 60 fps
(scene:wait *v*)
```

Drag orbits, shift-drag pans, scroll dollies. A viewer is a CLOS instance, not a set of
globals, so two windows can exist.

## Three packages ship, not one (decided 2026-08-29)

`scene` is `objc:`-dependent and macOS-only, so it cannot ship on `geom`'s terms -- both
WASM backends refuse a program that references `objc` (`.kb/objc.md`). It ships anyway, the
way `appkit.lisp` does: spliced only when the program references it, refused on WASM by the
existing `firstObjcReference` check, so a Mac user gets `(scene:viewer)` from a bare REPL
with nothing to copy. The argument is `.kb/objc.md`'s "Where the line goes" -- the binary
is what people install, and a binary user has no `examples/` directory.

**`metal` ships with it, as its own package.** `scene` is written over
`examples/macos/metal.lisp`, which is an EXAMPLE today, reached with
`(require :metal "metal.lisp")` and a relative path -- which a shipped `scene` cannot do.
Promoting it is the right answer rather than the forced one: `metal-triangle`,
`metal-cube`, `metal-robot-arm` and `metal-pagoda-garden` already share that file, which is
the same "a second consumer fixed the API" argument that promoted the `appkit` rungs. The
two rejected alternatives, so they are not re-litigated: absorbing what `scene` uses into
`scene`'s internals duplicates the layer, the pipeline, the frame loop and the buffer
helpers into two files that will not stay in step; leaving `scene` an example gives up the
reason for shipping `geom` at all, since a binary user who has `geom` and cannot draw with
it is in a strange position.

So the tree is three packages: `geom` on every backend, `metal` and `scene` on macOS.

### What promoting `metal` costs, and what it obliges

- `examples/macos/metal.lisp` is DELETED and its four consumers drop their `require` line.
  Check each still runs -- they are GUI programs, so by hand
  (`.kb/objc.md` names them); `metal-pagoda-garden.lisp` is the one that exercises the most
  of the surface.
- Its names become public and frozen. Audit them before that happens rather than after:
  the enum constants (`+triangle+`, `+point+`, `+cull-back+`, the compare and winding
  modes) are currently whatever the examples happened to need, and this item adds
  `MTLPrimitiveTypeLine` = 1 to them. Decide whether the whole enum family belongs or only
  the members a program actually names.
- `.kb/objc.md`'s "Metal" section names `examples/macos/metal.lisp` as the shared surface in
  several sentences and describes it as the twin of `webgl-common/gl.lisp`. Rewrite those
  rather than leaving them pointing at a deleted file; the twin relationship is still true
  and is worth keeping, but one side is now shipped and the other is not.
- A `MetalLibrary` splice class beside `AppKitLibrary`, a `PackageRegistry` entry, and
  reference docs in `doc/{en,ja}` -- a shipped package with no documentation is not
  shipped. `LibraryDefunPruner` matters here too: a program using `metal:frame` must not
  carry `metal:depth-state`.
- `metal` must remain usable WITHOUT `geom` or `scene`. It is the low-level surface the
  four existing examples use directly, and the promotion must not turn it into a private
  detail of the viewer.

## The design that must not be lost

**No triangle is touched by Lisp during a frame.** Each solid's model-space mesh
(`geom:mesh`) goes into an `MTLBuffer` of its own the first time it is drawn, and a frame
sets one 4x4 model matrix and one colour per solid and issues one draw call. This is the
parent's result 3: the alternative costs 380 ms a frame against 9.0 on a 60-solid model.
Two consequences a reviewer should check for:

- the per-solid GPU buffers live in `geom:user-data`, NOT in a hash table keyed by the
  solid (`567-an-eq-hash-table-is-not-identity-keyed.md`);
- the vertex function takes `vp` and `model` as separate uniforms and transforms the
  normal by `model` too, so a solid that moves needs no re-upload.

Lines (the ground grid, the world axes, every wireframe) go through a second pipeline with
`MTLPrimitiveTypeLine` = 1. The `metal` surface does not name that constant; add it there
rather than defining it locally, since it is exactly the kind of thing that surface
carries -- into whichever file the decision above leaves it in.

## Do

1. Promote `metal` first, on its own: move the file, splice it, audit and freeze its names,
   switch the four examples, rewrite the `.kb/objc.md` sentences, document it. It is a
   self-contained change that leaves the tree working, and `scene` has nothing to stand on
   until it lands.
2. Port `scene.lisp` over the shipped `metal`.
3. **Fix the one thing the spike deliberately did not.** Its `NSView` subclass routes every
   callback through a single `*active*` viewer, because AppKit callbacks are process-wide.
   Key the handler by the view that received the event instead -- `appkit::*actions*` is
   the precedent: one address-keyed table answers for every widget in that layer
   (`.kb/objc.md`, "Where the line goes"). Two viewers must orbit independently.
4. Per-solid axes and labels. The spike draws world axes only; a chain is much easier to
   read when a joint's own frame can be drawn (`geom:label-of` is already a slot). Decide
   whether a label needs text rendering at all -- an axis triad may be enough, and text in
   Metal is a large sub-problem.
5. Window resize. The spike fixes the drawable size at construction; `CAMetalLayer`'s frame
   and drawable size have to follow the view, and the projection's aspect with them.
6. Verify on ALL THREE carriers a GUI change must be checked on (CLAUDE.md, "After Task
   Completion"): `java -jar`, the native binary, and `-o Scene.class --class-name Scene`
   under `java` plus `-o scene.jar` under `java -jar`. The spike checked the first and third
   only. **The native binary is the one that matters most** -- it is the REPL people
   install and the reason `objc:` exists.
7. `CompileFrontend` must refuse a `.wasm` output for a program referencing `scene` with a
   message naming the package, exactly as it does for `objc`/`appkit` today.
8. An example under `examples/macos/`, and doc pages in `doc/{en,ja}`. GUI examples are not
   in `examples.yaml` (nothing can run them headless), so say so where the other GUI
   examples say it. Rewriting `examples/macos/metal-robot-arm.lisp`'s scene over `geom` +
   `scene` is the acceptance test the parent names -- its IK solver stays where it is.
9. `.kb/geom.md` gains the renderer's half: the no-triangle-per-frame invariant with its
   numbers, the uniform layout, the line-primitive addition to the `metal` surface, and the
   thread-0 facts it inherits (`appkit:timer` is the clock, every hop is `objc:on-main`,
   a callback runs on thread 0 with global dynamic bindings -- all in `.kb/objc.md`, cite
   rather than restate).

## Out of scope

Picking (click a solid, get the solid), shadows, textures, and anything that renders
without a display -- that last is
`568-an-offscreen-renderer-and-a-browser-twin.md`, and it is what will finally let a test
cover this file.
