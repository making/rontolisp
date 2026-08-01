# webgl-battlefront — a one-arena snow-battle skirmish, in Lisp

Play as you on a Hoth snow field. Move Minecraft-style with
<kbd>W</kbd><kbd>A</kbd><kbd>S</kbd><kbd>D</kbd> while the mouse aims (the page
takes Pointer Lock), <kbd>Space</kbd> to jump, <kbd>click</kbd> to attack,
<kbd>F</kbd> to swap between the lightsaber and the blaster, <kbd>R</kbd> to
restart; scroll zooms. Cut down the stormtroopers, bring the two AT-AT walkers
down, and once the walkers are gone **Vader** strides in — his red blade
deflects blaster fire, so finish the boss with your own lightsaber and light up
the sky.

On a touch-primary device (iPad, iPhone — anything the page detects via
`(pointer: coarse)`, no mouse required) the page swaps in a touch layer
instead: a floating joystick on the left half of the screen moves, dragging
the right half aims, and a FIRE / JUMP / F button cluster covers attack, jump
and weapon-swap. Deploying skips Pointer Lock entirely (iOS/iPadOS Safari
doesn't implement it) and just hides the start card. The touch build carries
**no instruction prose at all** — the start card is the title and the deploy
button, and the corner hint is just the source link. The controls are the
on-screen joystick and buttons, which say what they do; a paragraph explaining
them only grows the card until it covers the arena it is inviting you into.

Everything that makes it a game lives in `battlefront.lisp`, compiled ahead of
time to WebAssembly:

- **Movement and the aim camera** — camera-relative
  <kbd>W</kbd><kbd>A</kbd><kbd>S</kbd><kbd>D</kbd> acceleration on the open
  arena, the third-person follow camera whose yaw is the mouse aim (you face
  and fire along it), and its look-at / perspective matrices.
- **Blaster bolts** — one pool shared by you, the troopers and the walkers:
  travel, lifetimes, per-owner collisions, and the muzzle/impact sparks. Player
  bolts damage enemies; enemy bolts damage you.
- **The lightsaber** — a swing that both deals a frontal-arc hit and
  **deflects** any incoming bolt that reaches it, so blocking is a real defense.
- **The AI** — stormtroopers that close in and fire, the two ranged AT-AT
  walkers, and Vader, who stays dormant at the far edge until both walkers fall,
  then chases and swings in melee (and is immune to blaster fire).
- **The win/lose state, and every triangle** — you, the enemies, the walkers,
  the glowing blades and bolts are tessellated from scratch each frame. See
  "the shape vocabulary" below for what they are built out of; the short
  version is articulated tapered limbs with joint balls, rounded boxes,
  ellipsoids and cylinders, all with smooth per-vertex normals. Struck enemies
  flash red, and beating Vader sets off a sky of fireworks — the sparks and the
  muzzle/impact/damage flashes are all round too. The blades, bolts, damage
  flashes and fireworks are a second **additive-blended pass** over the same
  vertex buffer, so they bloom against the snow.
- **Materials** — every part also carries a **shine** value (matte cloth/snow
  vs. polished helmet domes, gun and saber metal), driving a Blinn-Phong
  specular highlight plus a soft sky-tinted rim light in the fragment shader,
  so the rounded surfaces read as lit from the low Hoth sun rather than flat.

## The shape vocabulary

Four primitives, all with smooth per-vertex normals (no face culling is
enabled, so winding never matters — only the explicit normal does):

- **`emit-limb`** — a tapered tube between two *arbitrary* 3D points, with the
  side normal tilted by the taper so a cone is lit as a cone. This is the one
  that changes what the cast can be: `emit-cylinder` is upright and
  `emit-cyl-beam` is horizontal, so a figure built only from those has straight
  vertical posts and straight horizontal planks for limbs, which is exactly why
  the walkers used to read as tables. With a free-standing segment, a leg
  becomes thigh + knee ball + shin at real angles.
- **`emit-rbox`** — a box with genuinely rounded edges: the Minkowski sum of a
  core box and a sphere, sampled by taking a point on the outer box, clamping
  it into the core, and placing the surface at `q + br * normalize(P - q)` —
  whose normal is that same direction. Splitting each face at the core boundary
  puts the sample lines exactly where the curvature starts, so a 3×3 grid a
  face is enough: a flat centre quad, four edge fillets, four corner fillets.
- **`emit-ellipsoid`** / **`emit-cylinder`** / **`emit-cyl-beam`** — heads,
  helmet domes, joint balls, gun barrels, blade rods, bolts, shadows, drifts,
  boulders and clouds.
- **soft box normals** — a plain `emit-box` can be stamped with per-corner
  normals bent toward the corner's own outward direction. The silhouette stays
  a box, but the shading gradient is a bevelled edge's, which is most of what
  separates a moulded panel from a carton at gameplay distance — and it costs
  not one extra vertex.

Two things keep that affordable, both of which buy detail where it is *visible*
rather than uniformly:

- **`set-lod`** picks a per-figure detail tier from apparent size (world height
  over distance to the eye), scaling every ring and band count.
- **`part-rbox`** then asks the same question about the individual part: a
  fillet costs nine times a plain box, so it is spent only when *that part's*
  largest half-extent over its distance clears a threshold. A torso at arm's
  length earns its fillet; the 2 cm brow ridge on the same figure never will.

Two consequences worth knowing if you edit the models. The walker's legs are
**solved, not drawn**: the foot is animated (swinging fore and aft, lifting only
while it travels forward) and the knee is placed by the two-link inverse
kinematics the fixed thigh and shin lengths force — that is what makes it
stride instead of slide. And Vader's cape is a **parametric surface**, sampled
over (down the back × across the back) with the flare and the folds as
functions of those two parameters and normals taken from finite differences of
the same function, because a cape is the one part of him a box can never stand
in for.

The horizon is built from the same `emit-limb`: a ring of squat cones with
off-centre apexes, in a near row of hills and a far row of peaks, standing on
an apron of very wide low cones. It replaced four tall boxes, which from inside
the arena is a flat grey wall with two vertical corners running up the sky.

The JavaScript side is the same one-line WebGL2 host boundary as the other
`webgl-*` demos — bindings generated from the shared `gl.wit` (see
[`../webgl-common/`](../webgl-common)), plus two extra staging entries
(`setEmissive`, `setShine`) so a vertex can glow or take a specular highlight —
plus the Pointer-Lock mouse and keyboard forwarding (the page only maps input
to small integers; the aim, the held state and every rule are Lisp's business)
and the HUD.

**Live demo:** <https://making.github.io/rontolisp/webgl-battlefront/> (this
directory is published as a subpath of the GitHub Pages site by
`.github/workflows/pages.yaml`).

## Building

```bash
./build.sh          # battlefront.lisp -> battlefront.wasm (--no-wasi --optimize)
# the page imports the generated ../webgl-common/gl-imports.js, so serve
# examples/browser rather than this directory:
jwebserver -p 8000 --directory ..
# open http://localhost:8000/webgl-battlefront/
```

`build.sh` uses a `rontolisp` binary on `PATH` if it finds one, otherwise the
built exec JAR at the repo root. `--no-wasi` makes the module a reactor whose
only imports are host functions (`gl`, `canvas`, `math` — the ones
`battlefront.lisp` declares with `rontolisp:wasm-import` plus the WebGL2 entries
the shared `gl` package binds from `../webgl-common/gl.wit`); `--optimize`
tree-shakes the runtime and the unused WebGL entries, leaving `battlefront.wasm`
importing only the ~40 functions it actually reaches. The page instantiates the
module, calls `_initialize()` (which compiles the shaders and bakes the snow
field — from Lisp), then calls the exported `frame` once per animation tick.

Open the DevTools console and poke the game directly: the module's exports are
on `window.lisp` (`lisp.getHp()`, `lisp.getWeapon()`, `lisp.restart()`, ...).

## A note on the geometry math

The **game** math is the `linalg` package throughout, like `webgl-robot-arm`.
Every coordinate — you, the enemies, the bolts, the camera, the aim frame — is a
**packed single-float vector** (`#f(x y z)`; runtime ones are built with
`linalg:from-list … 'single-float`), and movement, distance and heading are
`linalg:add` / `sub` / `mul` / `dot` / `norm` straight on those vectors
(single-float in, single-float out — no boxing path). The view-projection is
`linalg:matmul` of two `(4 4)` matrices, and `upload-vp` flattens its
**transpose** (`linalg:flatten` of `linalg:transpose`) into the column-major run
`gl:uniform-matrix4fv` wants. There are no bespoke vector helpers for any of
that — the `linalg:` calls are the vector algebra.

The **tessellation** inner loop deliberately is not. A box's eight corners used
to be one `linalg:matmul` of the 3×3 yaw rotation against a `(3 8)` local-corner
matrix plus a broadcast `linalg:add` of the centre, which is the right tool for
a camera matrix and the wrong one for eight corners: four array allocations and
a general nested-loop matmul, paid per box per frame. With the cast now emitting
hundreds of boxes and rounded boxes a frame, that allocation dominated the frame
time; `emit-box` writes the same rotation out as scalars into one corner buffer
allocated once. The same reasoning covers the cylinder / ellipsoid / limb /
rounded-box samplers: each vertex is a handful of scalar `sin`/`cos` calls, and
packing those into `linalg` arrays would only add allocation.

One more constraint shapes those signatures. The WASM backend's callable types
stop at **seven parameters**; a wider fixed-arity `defun` still compiles, but
only because the compiler rewrites it to bundle the surplus arguments into a
freshly consed list at every call site. That is invisible in a cold helper and
ruinous in one called per triangle, so every function that runs per vertex or
per triangle is kept at seven parameters or fewer, and whatever is constant
across a whole primitive (the ellipsoid's centre/radii/yaw, the rounded box's
extents, a limb's far radius) is latched in a global instead of threaded through
the signature. That is why there is no `emit-tri`: three `emit-vertex` calls
cost nothing, an 18-parameter helper costs twelve cons cells a triangle.
