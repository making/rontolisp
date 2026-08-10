# webgl-battlefront — a one-arena snow-battle skirmish, in Lisp

Move with <kbd>W</kbd><kbd>A</kbd><kbd>S</kbd><kbd>D</kbd> while the mouse aims
(the page takes Pointer Lock), <kbd>Space</kbd> jumps, <kbd>click</kbd> attacks,
<kbd>F</kbd> swaps lightsaber and blaster, <kbd>R</kbd> restarts, scroll zooms.
Cut down the stormtroopers, bring the two walkers down, and the boss strides in
— his blade deflects blaster fire, so finish him with your own.

On a touch-primary device the page swaps in a touch layer instead: a floating
joystick moves, dragging the right half aims, and a FIRE / JUMP / F cluster
covers the rest. It skips Pointer Lock entirely (iOS Safari has none) and
carries no instruction prose — the on-screen controls say what they do.

**Live demo:** <https://making.github.io/rontolisp/webgl-battlefront/>

## What is in the Lisp

Everything that makes it a game lives in `battlefront.lisp`, compiled ahead of
time to WebAssembly:

- **Movement and the aim camera** — camera-relative acceleration, the
  third-person follow camera whose yaw is the mouse aim, and its look-at /
  perspective matrices.
- **Blaster bolts** — one pool shared by you, the troopers and the walkers:
  travel, lifetimes, per-owner collisions, muzzle and impact sparks.
- **The lightsaber** — a swing that deals a frontal-arc hit *and* **deflects**
  any incoming bolt that reaches it, so blocking is a real defense.
- **The AI** — troopers that close in and fire, two ranged walkers, and a boss
  who stays dormant until both walkers fall, then chases and swings in melee.
- **Every triangle** — you, the enemies, the walkers, the blades and bolts are
  tessellated from scratch each frame. The blades, bolts, damage flashes and
  fireworks are a second **additive-blended pass** over the same vertex buffer,
  so they bloom against the snow.
- **Materials** — each part carries a *shine* value driving a Blinn-Phong
  highlight plus a soft rim light, so rounded surfaces read as lit rather than
  flat.

JavaScript is the same one-line WebGL2 host boundary as the other `webgl-*`
demos — bindings generated from the shared `gl.wit` (see
[`../webgl-common/`](../webgl-common)) plus two staging entries (`setEmissive`,
`setShine`) — the Pointer-Lock mouse, and the HUD. The page maps input to small
integers; every rule is Lisp's.

## The shape vocabulary

Four primitives, all with smooth per-vertex normals (face culling is off, so
only the explicit normal matters):

- **`emit-limb`** — a tapered tube between two *arbitrary* 3D points, with the
  side normal tilted by the taper so a cone is lit as a cone. This is the one
  that changes what the cast can be: with upright cylinders and horizontal
  beams alone a figure has straight posts for limbs, which is why the walkers
  used to read as tables. With a free-standing segment, a leg becomes thigh +
  knee ball + shin at real angles.
- **`emit-rbox`** — a box with genuinely rounded edges: the Minkowski sum of a
  core box and a sphere, sampled by taking a point on the outer box, clamping
  it into the core, and placing the surface at `q + br * normalize(P - q)` —
  whose normal is that same direction. Splitting each face at the core boundary
  puts the sample lines where the curvature starts, so a 3×3 grid per face is
  enough.
- **`emit-ellipsoid` / `emit-cylinder` / `emit-cyl-beam`** — heads, helmet
  domes, joint balls, barrels, blade rods, bolts, shadows, drifts, boulders.
- **soft box normals** — a plain `emit-box` stamped with per-corner normals
  bent toward each corner's outward direction. The silhouette stays a box but
  the shading gradient is a bevelled edge's, for not one extra vertex.

Two things keep that affordable by buying detail where it is *visible*:
`set-lod` picks a per-figure detail tier from apparent size, and `part-rbox`
then asks the same question about the individual part — a fillet costs nine
times a plain box, so it is spent only when that part's largest half-extent
over its distance clears a threshold.

Two consequences worth knowing if you edit the models. The walker's legs are
**solved, not drawn**: the foot is animated and the knee placed by the two-link
inverse kinematics the fixed thigh and shin lengths force — that is what makes
it stride instead of slide. And the boss's cape is a **parametric surface**,
sampled over (down the back × across the back), normals taken from finite
differences of the same function, because a cape is the one part a box can
never stand in for.

The horizon is the same `emit-limb`: a ring of squat cones with off-centre
apexes in two rows, on an apron of very wide low cones. It replaced four tall
boxes, which from inside the arena is a flat grey wall with two corners running
up the sky.

## Building

```bash
./build.sh          # battlefront.lisp -> battlefront.wasm (--no-wasi --optimize)
# the page imports the generated ../webgl-common/gl-imports.js, so serve
# examples/browser rather than this directory:
jwebserver -p 8000 --directory ..
# open http://localhost:8000/webgl-battlefront/
```

`build.sh` uses a `rontolisp` binary on `PATH` if it finds one, otherwise the
built exec JAR. `--no-wasi` makes the module a reactor whose only imports are
host functions; `--optimize` tree-shakes the runtime and the unused WebGL
entries, leaving only the ~40 the program reaches. The page instantiates the
module, calls `_initialize()` (which compiles the shaders and bakes the snow
field, from Lisp), then calls the exported `frame` per animation tick.

The module's exports are on `window.lisp`, so the DevTools console can poke the
game directly: `lisp.getHp()`, `lisp.getWeapon()`, `lisp.restart()`.

## A note on the geometry math

The **game** math is the `linalg` package throughout. Every coordinate is a
packed single-float vector (`#f(x y z)`), and movement, distance and heading are
`linalg:add`/`sub`/`mul`/`dot`/`norm` straight on those vectors — single-float
in, single-float out, no boxing path. The view-projection is `linalg:matmul` of
two `(4 4)` matrices, flattened from its **transpose** into the column-major run
`gl:uniform-matrix4fv` wants. There are no bespoke vector helpers: the `linalg:`
calls are the vector algebra.

The **tessellation** inner loop deliberately is not. A box's eight corners used
to be one `linalg:matmul` plus a broadcast add — the right tool for a camera
matrix and the wrong one for eight corners: four array allocations and a general
nested-loop matmul, paid per box per frame. With hundreds of boxes a frame that
allocation dominated; `emit-box` writes the same rotation out as scalars into
one corner buffer allocated once. The same holds for the cylinder / ellipsoid /
limb / rounded-box samplers, where each vertex is a handful of scalar
`sin`/`cos` calls.

One more constraint shapes those signatures. The WASM backend's callable types
stop at **seven parameters**; a wider fixed-arity `defun` still compiles, but
only because the compiler bundles the surplus arguments into a freshly consed
list at every call site — invisible in a cold helper, ruinous in one called per
triangle. So every per-vertex function stays at seven parameters or fewer, and
whatever is constant across a primitive is latched in a global instead. That is
why there is no `emit-tri`: three `emit-vertex` calls cost nothing, an
18-parameter helper costs twelve cons cells a triangle.
