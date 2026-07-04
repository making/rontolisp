# webgl-platformer — a one-stage 3D platformer, in Lisp

Run with <kbd>W</kbd><kbd>A</kbd><kbd>S</kbd><kbd>D</kbd> (<kbd>W</kbd> runs
forward, into the screen), jump with <kbd>Space</kbd>, restart with
<kbd>R</kbd>; drag to orbit the camera, scroll to zoom (steering is
camera-relative, so <kbd>W</kbd> stays "into the screen" from any angle).
Stomp the walkers, grab the coins, cross the pits and reach the flag pole in
front of the castle.

Everything that makes it a game lives in `platformer.lisp`, compiled
ahead of time to WebAssembly:

- **Physics** — gravity, variable jump height (release <kbd>Space</kbd>
  early for a short hop), a jump buffer and coyote time, ground/air
  acceleration.
- **Collision** — classic per-axis AABB resolution against the level
  blocks; the same block list drives both the collision arrays and the
  baked level mesh.
- **Enemies** — patrolling walkers; land on one while falling and it is
  squashed (with a bounce), touch it any other way and you are back at the
  start.
- **Coins, the goal and the HUD state** — pickups, the flag-pole trigger,
  the run clock and the fall counter, all polled by the page through
  exported functions (`getCoins`, `getState`, `getTime`, ...).
- **The camera and every triangle** — the orbiting follow camera (drag and
  scroll arrive through the exported `orbit`/`zoom`, as in
  [`../webgl-robot-arm/`](../webgl-robot-arm)), its look-at and
  perspective matrices, and the whole world tessellated from yaw-rotated
  boxes each frame: the level and scenery are baked once at load time, the
  robot explorer, the walkers and the spinning coins are re-emitted every
  frame after them in the same vertex buffer.

The JavaScript side is the same one-line WebGL2 host boundary as the other
`webgl-*` demos (see [`../webgl-common/`](../webgl-common)), plus keyboard
forwarding — the page only maps key events to small integers; held state,
buffering and coyote time are Lisp's business — and the HUD.

**Live demo:** <https://making.github.io/rontolisp/webgl-platformer/> (this
directory is published as a subpath of the GitHub Pages site by
`.github/workflows/pages.yaml`).

## Building

```bash
./build.sh          # platformer.lisp -> platformer.wasm (--no-wasi --optimize)
jwebserver -p 8000 --directory .
# open http://localhost:8000/
```

`--no-wasi` makes the module a reactor whose only imports are the host
functions declared with `rontolisp:wasm-import` (`gl`, `canvas`, `math`);
`--optimize` tree-shakes the runtime and the unused entries of the shared
`gl` package. The page instantiates the module, calls `_initialize()` (which
compiles the shaders, parses the stage and bakes its mesh — from Lisp), then
calls the exported `frame` once per animation tick.

Open the DevTools console and poke the game directly: the module's exports
are on `window.lisp` (`lisp.getPx()`, `lisp.restart()`, ...).
