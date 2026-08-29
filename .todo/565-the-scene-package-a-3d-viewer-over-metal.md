# The `scene` package: a 3D viewer for `geom` solids, over Metal

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

## Where it belongs, and what it drags with it -- decide this FIRST

`scene` is `objc:`-dependent and macOS-only, so it cannot ship on `geom`'s terms -- both
WASM backends refuse a program that references `objc` (`.kb/objc.md`). The choice is
between shipping it the way `appkit.lisp` ships (spliced only when referenced, refused on
WASM by the existing `firstObjcReference` check, so a Mac user gets `(scene:viewer)` from a
bare REPL) and leaving it in `examples/macos/`. The parent's argument for shipping `geom`
-- "the binary is what people install and a binary user has no `examples/` directory to
copy from", `.kb/objc.md`'s "Where the line goes" -- applies here too and is probably
decisive.

**But shipping `scene` forces a decision about `metal` as well, and that is the harder
half.** `scene` is written over `examples/macos/metal.lisp`, which is an EXAMPLE today: a
program reaches it with `(require :metal "metal.lisp")` and a relative path. A shipped
`scene` cannot do that -- there is no `examples/` directory beside an installed binary.
Three ways out, and the item has to pick one on the record:

1. **Ship `metal` too, as its own package**, next to `geom` and `scene`. It has earned it
   independently of this feature -- `metal-triangle`, `metal-cube`, `metal-robot-arm` and
   `metal-pagoda-garden` all already share it, which is the same "second consumer fixed the
   API" argument that promoted the `appkit` rungs (`.kb/objc.md`, "Where the line goes").
   The cost is real: `metal.lisp` becomes a supported surface with reference docs and a
   compatibility obligation, `examples/macos/metal.lisp` is deleted and its four consumers
   switch to the shipped package, and `.kb/objc.md`'s "Metal" section -- which names that
   file as the shared surface in several places -- is rewritten. Its enum constants
   (`+triangle+`, `+point+`, the cull and compare modes) become public names, so audit them
   before they are frozen, and add the `MTLPrimitiveTypeLine` = 1 that this item needs.
2. **Absorb what `scene` uses into `scene`'s own internals** and leave the example file
   alone. Cheapest, and wrong: the layer, the pipeline, the frame loop and the buffer
   helpers would then exist twice, in two files that must stay in step and will not.
3. **Leave `scene` an example too**, so both keep the `require`-by-path shape. Coherent,
   and it gives up the reason for shipping `geom` at all -- a binary user who has `geom`
   and cannot draw with it is in a strange position.

(1) is the recommendation. Whatever is chosen, record the reason in `.kb/geom.md` and, if
`metal` moves, in `.kb/objc.md` beside the sentences it invalidates.

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

1. Settle the placement question above -- `scene`, and `metal` with it -- before porting a
   line. It decides where the file goes, what its package name may be, and whether four
   existing examples and a `.kb` section move with it.
2. Port `scene.lisp`.
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
