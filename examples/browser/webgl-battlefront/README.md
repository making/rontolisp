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
doesn't implement it) and just hides the start card.

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
  the glowing blades and bolts are tessellated each frame from yaw-rotated
  boxes for the angular parts (armor plates, packs, belts — genuinely flat in
  the source designs, plus the AT-AT's deliberately mechanical legs) and from
  smooth-normaled **cylinders and ellipsoids** (`emit-cylinder` /
  `emit-cyl-beam` / `emit-ellipsoid`, and the `part-cyl` / `part-cyl-beam` /
  `part-ellipsoid` wrappers that mirror `part`) for the round ones — heads,
  helmet domes, limbs, gun barrels, saber/blade rods, AT-AT hip joints,
  blaster bolts, ground shadows, snow-drift mounds, ice boulders and clouds. A
  low-poly prism still reads as round because its per-vertex normal is the
  true radial direction, not a flat per-face one, so the lit shader gradient
  does the rest. Struck enemies flash red, and beating Vader sets off a sky of
  fireworks — the sparks and the muzzle/impact/damage flashes are all round
  too. The blades, bolts, damage flashes and fireworks are a second
  **additive-blended pass** over the same vertex buffer, so they bloom
  against the snow.
- **Materials** — every part also carries a **shine** value (matte cloth/snow
  vs. polished helmet domes, gun and saber metal), driving a Blinn-Phong
  specular highlight plus a soft sky-tinted rim light in the fragment shader,
  so the rounded surfaces read as lit from the low Hoth sun rather than flat.

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

The geometry is the `linalg` package throughout, like `webgl-robot-arm`. Every
coordinate — you, the enemies, the bolts, the camera, the aim frame — is a
**packed single-float vector** (`#f(x y z)`; runtime ones are built with
`linalg:from-list … 'single-float`), and the game math is `linalg:add` / `sub` /
`mul` / `dot` / `norm` straight on those vectors (single-float in, single-float
out — no boxing path). A box's eight corners are one `linalg:matmul` of the 3×3
yaw rotation against a `(3 8)` local-corner matrix, then a broadcast `linalg:add`
of the center; the view-projection is `linalg:matmul` of two `(4 4)` matrices,
and `upload-vp` flattens its **transpose** (`linalg:flatten` of
`linalg:transpose`) into the column-major run `gl:uniform-matrix4fv` wants. There
are no bespoke vector helpers for any of that — the `linalg:` calls are the
vector algebra. The cylinder/ellipsoid primitives are the one exception: each
vertex is a handful of scalar `sin`/`cos` calls (a ring or a lat/long grid),
cheap enough per-vertex that packing them into `linalg` arrays would only add
allocation.
