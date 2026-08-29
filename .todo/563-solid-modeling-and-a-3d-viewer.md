# Solid modeling and a 3D viewer, in the image

Difficulty: High

Parent item. Filed 2026-08-29 after the feasibility spike in
`563-solid-modeling-and-a-3d-viewer/`, which is where every number below comes from --
read its README before starting any member.

## What this is for

rontolisp can already open a native window and drive the GPU from the REPL (`.kb/objc.md`;
`examples/macos/metal-*.lisp`), but everything that appears in one of those windows is
built by hand: a Lisp program that wants a cylinder writes the tessellation loop itself.
The missing rung is a MODEL -- solids you construct by name, hang off a kinematic chain,
move by transforms, measure, and see. With it, the sequence

```lisp
(defvar *v* (scene:viewer))
(scene:add *v* (geom:cylinder :radius 60 :height 140))
(scene:fit *v*)
```

is three lines in a bare REPL, and a robot arm, a mechanism, a lattice or a printed part is
an ordinary Lisp program with a picture attached.

Everything below is an original design, worked out in the spike: the class model, the
naming, the call shapes, the mesh representation and the renderer's structure. A few of the
decisions are worth stating up front because they are load-bearing and a reader may expect
otherwise -- a transform is a VALUE rather than a superclass, a node HAS a transform rather
than being one, mutators take a named `:frame` rather than a positional flag, primitives
are noun constructors taking keywords, and there is no message-send protocol at all: plain
functions and CLOS accessors throughout. Members must keep it that way. Design against the
problem.

## Three packages, and where the line falls

The spike's first result decides the architecture. The modeling half is CLOS over `linalg`
and touches nothing else, and `oracle.lisp` prints byte-identical output on the
interpreter, a compiled JVM class, WASM preview 1 and the WASI 0.3 component. So:

- **`geom` is a SHIPPED, backend-independent Lisp library**, the `linalg.lisp` /
  `appkit.lisp` pattern (`.kb/linalg.md`, `.kb/objc.md`): source under
  `src/main/resources/am/ik/rontolisp/eval/`, a `GeomLibrary` splice class beside
  `LinalgLibrary`, reachable from a bare REPL with nothing required. It works in the
  browser playground too, which is what makes it worth shipping rather than exiling to
  `examples/macos/`.
- **`scene` is macOS-only** and SHIPS too, over `objc:`/`appkit:`/Metal, the `appkit.lisp`
  way: spliced when referenced, refused on WASM for the same reason every `objc:` program
  is. The binary is what people install, and a binary user who has `geom` and cannot draw
  with it is in a strange position.
- **`metal` ships with it** (decided 2026-08-29). `scene` is written over the Metal surface
  that lives in `examples/macos/metal.lisp` today, reached by relative path -- which a
  shipped `scene` cannot do. It is promoted rather than duplicated, and it has earned that
  on its own: four existing examples already share it. The cost is that its names become
  public, `examples/macos/metal.lisp` is deleted, its four consumers move, and
  `.kb/objc.md`'s "Metal" section is rewritten. Member 2 owns the work and does it FIRST,
  as a self-contained change that leaves the tree working.

## Members

1. `564-the-geom-package-transforms-scene-graph-and-solids.md` -- the modeling half:
   `transform`, `node`, `solid`, the primitive constructors, the cached mesh, the
   measurements. Everything else depends on it. **Start here.**
2. `565-the-scene-package-a-3d-viewer-over-metal.md` -- promoting `metal` to a shipped
   package, then the window over it: orbit/pan/dolly camera, ground grid, world and body
   axes, solid/wireframe shading, `fit`, an animation hook.
3. `566-constructive-solid-geometry-for-geom-solids.md` -- union, difference, intersection
   and a planar section. The hard half of solid modeling, and independent of the viewer.
4. `567-an-eq-hash-table-is-not-identity-keyed.md` -- a defect the spike ran into and
   worked around. Not scoped to this feature; filed here because this is where it surfaced.
5. `568-an-offscreen-renderer-and-a-browser-twin.md` -- what makes the viewer TESTABLE (no
   test may open a window) and what takes `geom` beyond macOS.

Members 1-2 are the feature. 3 and 5 extend it, 4 is a bug found under it; none of the
three blocks the first two.

## What must be true when the parent closes

- `geom` ships in the jar and in the native binary, works on all four backends, and has a
  `.kb/geom.md` naming the cross-backend pin.
- `metal` and `scene` ship beside it, `scene` opens a window from the interpreter, the
  native binary and a compiled JVM class, and `doc/{en,ja}` document all three packages
  with runnable examples.
- `examples/macos/metal.lisp` is gone and its four consumers run against the shipped
  `metal`, checked by hand as GUI programs must be.
- The renderer does no per-triangle work in a frame -- the spike's result 3 is the
  invariant, and `.kb/geom.md` records the two numbers so a later change that regresses it
  is visible.

## Out of scope for the whole tree

Physics, collision response, mesh file formats (STL/OBJ/glTF import or export), textures,
inverse kinematics, and any robot-description format. Each is a reasonable follow-up and
none is needed to make the two halves above worth having. `examples/macos/metal-robot-arm.lisp`
already carries a hand-written IK, and rewriting it over `geom` is a good acceptance test
for member 2 -- but the SOLVER does not move into `geom`.
