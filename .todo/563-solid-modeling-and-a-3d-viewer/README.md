# The solid-modeling + viewer feasibility spike (2026-08-29)

Throwaway probes kept for reproducibility, NOT project code: they are outside `src/`, are
not in the reactor, are not formatted by `spring-javaformat:apply`, and nothing builds or
tests them. They exist so the claims in `../563-solid-modeling-and-a-3d-viewer.md` and its
members can be re-derived on another Mac.

Machine: Apple M4 Max, macOS 26.3.1, Oracle GraalVM 25.0.3 (aarch64), wasmtime 47.0.3.
Every number was taken against a jar built at `3054f418` and re-checked against `d933cd34`.
`scene.lisp` requires `examples/macos/metal.lisp` by NAME, so copy it in before running
anything that draws -- it is not duplicated here.

| file | what it answers |
|---|---|
| `geom.lisp` | the modeling half: rigid `transform` values, a scene-graph `node`, boundary-represented `solid`s, eight primitive constructors, bounds / volume / centroid / area, and the cached model-space mesh. NO `objc` -- the question it settles is whether this half is backend-independent |
| `scene.lisp` | the viewing half: a window over `metal.lisp` with an orbit/pan/dolly camera, a ground grid, world axes, and solid / wireframe shading |
| `demo.lisp` | both at once: every primitive on a shelf, plus a three-joint chain animating |
| `oracle.lisp` | the numeric check -- closed-form volumes and areas, transform algebra, a joint chain's forward kinematics, the mesh cache |
| `bench.lisp` | the measurement the renderer's design rests on: per-frame triangle soup against a cached mesh and a per-solid matrix |
| `repro.lisp` | the hash-table defect this spike ran into, minimized: a key's cost is exponential in its shared structure |

## Running them

```bash
JAR=$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
cd .todo/563-solid-modeling-and-a-3d-viewer
cp ../../examples/macos/metal.lisp .          # scene.lisp requires it by name

java -jar $JAR oracle.lisp     # numbers, no window
java -jar $JAR bench.lisp      # ~40 s: the A/B the design rests on
java -jar $JAR demo.lisp       # the window
```

The four-backend check that decides where the modeling half ships -- all four print
byte-identical output:

```bash
java -jar $JAR oracle.lisp
java -jar $JAR oracle.lisp -o Oracle.class --class-name Oracle && java Oracle
java -jar $JAR oracle.lisp -o oracle.wasm && wasmtime run oracle.wasm
java -jar $JAR oracle.lisp -o oracle-comp.wasm --component && wasmtime run oracle-comp.wasm
```

And the viewer as a distributable artifact -- the binding travels inside the class
(`.kb/objc.md`, "The JVM backend"):

```bash
java -jar $JAR demo.lisp -o SpikeScene.class --class-name SpikeScene && java SpikeScene
```

## The design, in one paragraph

The question the spike asked was whether rontolisp can be a Lisp image in which you
construct solids, hang them off a kinematic chain, and see the result in a window that is
part of the same process. Everything in this directory is an original answer to it: a
`transform` is a VALUE with no identity and no cache; a `node` HAS a local transform rather
than being one, so a solid, a joint frame and a camera target are all nodes with no slot
any of them does not use; mutators take a named `:frame` rather than a positional flag;
primitives are noun constructors taking keywords; a solid's mesh is cached on the solid,
in model space, because a rigid solid's triangles never change and only its pose does; and
there is no message-send protocol -- plain functions and CLOS accessors throughout.

## The five results

**1. The modeling half is backend-independent, so it ships like `linalg.lisp`.**
`geom.lisp` is CLOS over `linalg` and nothing else. `oracle.lisp` prints the same bytes on
the interpreter, a compiled JVM class, WASM preview 1 and the WASI 0.3 component. There is
no reason to confine it to macOS -- the same solids can be tessellated in the browser
playground and drawn with WebGL, which is what makes this a shipped `geom` package rather
than a member of `examples/macos/`.

**2. The numbers converge from below, as a polyhedral approximation must.**

| solid | measured volume | closed form | error |
|---|---|---|---|
| `(box '(100 200 300))` | 6000000.0 | 6000000 | exact (area 220000 exact too) |
| `(cylinder :radius 50 :height 100 :sides 64)` | 784137.12 | 785398.16 | -0.16% |
| `(sphere :radius 50 :sides 32 :stacks 24)` | 518015.50 | 523598.77 | -1.07% |
| `(torus :radius 60 :tube 20 :sides 48 :rings 24)` | 467011.56 | 473741.01 | -1.42% |
| `(cone :radius 50 :height 120 :sides 64)` | 313654.85 | 314159.27 | -0.16% |

Volume is the divergence theorem over the mesh triangles, so it doubles as a winding
check: a facet wound the wrong way subtracts, and every row above would be grossly wrong
rather than slightly small. `compose` and `invert` agree with applying the transforms
directly (exactly, and to float32 respectively), and a three-node chain's forward
kinematics land where they are computed by hand.

**3. A renderer must not re-tessellate per frame. This is the load-bearing measurement.**

`bench.lisp`, on a 30-joint chain of cylinders and spheres -- 60 solids, 13,800 triangles,
the scale of one articulated model:

| design | per frame |
|---|---|
| A: transform every vertex into world space every frame | **380 ms** (2.6 fps) |
| B: cached model-space mesh + one 4x4 matrix per solid | **9.0 ms** (~110 fps of headroom) |

42x, and it is a design difference rather than an optimization. A rigid solid's triangles
never change; only its pose does. So the triangles belong to the solid (tessellated once,
into MODEL space, cached in a slot) and the pose belongs to the frame (one matrix, handed
to the GPU as a per-draw uniform). B pays 179 ms once at load time for those 60 solids.
`scene.lisp` is built this way: no triangle is touched by Lisp during a frame.

**4. The window works under `java -jar` and as a compiled class.** `demo.lisp` draws the
ground grid, the world axes, six primitives and an animating three-joint chain, identically
under both. Checked by eye; no test opens a window (`.kb/objc.md`), and no screenshot is
checked in here -- a full-desktop capture carries whatever else was on the screen. To take
one of the window alone, print the window number and pass it to `screencapture -x -l<id>`,
the way `../512-a-native-macos-gui-from-the-repl-through-ffm/README.md` documents.

`metal.lisp` needed no change -- the one constant it does not carry,
`MTLPrimitiveTypeLine` = 1, `scene.lisp` defines locally, and any renderer that draws a
grid or a wireframe needs it. Where that constant ends up is part of a larger question the
spike leaves open and `../565-the-scene-package-a-3d-viewer-over-metal.md` owns: `scene` is
written over a Metal surface that is an EXAMPLE today, reached by relative path, so a
shipped `scene` cannot reach it at all.

**5. A defect found on the way: a hash table's key cost is exponential in shared structure.**
The renderer's first cut kept its GPU buffers in an `(make-hash-table :test 'eq)` keyed by
the solid. That works while the solids are unparented and stops returning the moment they
are attached to a scene graph. Two things are wrong, and `repro.lisp` separates them.

`:test 'eq` is accepted and ignored -- every table places its keys by a STRUCTURAL hash
(`.kb/hash-tables.md`; the narrowing half is `../012-hash-table-test-semantics.md`). And
that hash caps recursion DEPTH at 64 levels but not WORK, while the number of
root-to-leaf PATHS through a graph with sharing is exponential in its depth. So an `equal`
table keyed by a DAG of n shared conses -- no cycle anywhere -- costs 2^n:

| n conses | 8 | 16 | 20 | 22 | 24 | 26 |
|---|---|---|---|---|---|---|
| one `gethash` | 2 ms | 1 ms | 3 ms | 9 ms | 33 ms | 130 ms |

An instance that knows its parent is far worse, because each level adds several cons levels
of fan-out: one `gethash` on an EMPTY `eq` table, keyed by a node two links down a
parent-linked chain, takes 61 ms at depth 1 and did not return in 55 s at depth 2. It is
not interpreter-only -- a compiled JVM class behaves the same (37 ms, then no return), and
the WASM output did not finish either.

`eq` is defined as identity, so this is a real bug with a real user-visible cost, and it is
its own item (`../567-an-eq-hash-table-is-not-identity-keyed.md`). The spike works around
it by keeping the renderer's per-solid state in a `user-data` slot on the solid -- which is
the right design anyway, and what `scene.lisp` does now.

## What the spike did not settle

- **Boolean operations** were not attempted at all. Union, difference and intersection of
  two boundary representations are the hard half of solid modeling and are their own item.
- **The native binary** was not measured. It is the REPL people install and the reason
  `objc:` exists, so the viewer item has to check it.
- **Two windows in one process.** `scene.lisp` routes its AppKit callbacks through a
  single `*active*` viewer, because the callbacks are process-wide. A shipped viewer has to
  key the handler by the view that received the event.
- **Nothing here is tested.** The modeling half is testable as ordinary Lisp; the renderer
  is not, until something can render without a display.
- **Where the Metal surface lives.** `scene.lisp` reaches it by relative path, which only
  an example can do. If `scene` ships, `metal` has to ship with it or be duplicated inside
  it -- `../565-the-scene-package-a-3d-viewer-over-metal.md` owns that decision and must
  make it before it ports anything.
