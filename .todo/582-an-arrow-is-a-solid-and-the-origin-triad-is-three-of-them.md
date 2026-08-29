# An arrow is a solid, and the origin triad is three of them

Difficulty: Medium

Filed 2026-08-29 from a user report against the viewer shipped by
`.todo/565`. The origin indicator is furniture today: `scene:axes` is a MODE
(`:world` by default), the geometry is three one-unit line segments in a private
buffer (`scene::%build-axes`), and it is drawn with `metal:+line+` under a model matrix
scaled by `0.16 * distance` so it stays legible as the camera pulls back.

Three things are wrong with that, and they are one thing: the origin indicator is not an
object.

- **It cannot be given a thickness.** `metal:+line+` has no width to set -- a thick arrow
  is geometry or it is nothing.
- **It appears whether or not it was asked for.** `:world` is the initform, so every
  viewer draws a triad at the origin until told not to.
- **It has no tips.** Three segments read as a cross, not as three arrows.

## What is wanted

An `arrow` the caller CONSTRUCTS, with a thickness, placed where the caller says. Placed
at `#f(0.0 0.0 0.0)` the result should look like what the viewer draws today, so nothing
is lost by the default going away.

## The load-bearing decision: `geom`, not `scene`

An arrow with a shaft thickness and a pointed tip is a SOLID -- a shaft and a cone -- so
it belongs in `geom` beside `geom:cylinder` and `geom:cone`, not in the viewer's private
furniture. That buys, for free: a cached mesh on the normal draw path (so the renderer's
"no per-triangle work in a frame" invariant covers it, `.kb/geom.md`), bounds and
`geom:volume`, a place on a kinematic chain, CSG, all four backends, and the browser twin
(`examples/browser/webgl-solids/`) without a line of renderer code. The viewer keeps only
the part that is genuinely about viewing.

Follow the parent's design rules (`563`, "a few of the decisions are worth stating up
front"): a noun constructor taking keywords, no message-send protocol, plain functions and
CLOS accessors. `geom:cylinder` and `geom:cone` are the shape to copy, including `:sides`,
`:color` and `:label`.

Build the mesh directly rather than `geom:union`-ing a cylinder and a cone. The union is
correct and would work, but it is a BSP clip (`.kb/geom.md`, "Boolean operations") for a
composition whose seam is known at construction time, and the arrow is the one solid a
viewer may build several of per frame's worth of furniture.

## Decisions the member has to make, and record

- **Does the world triad still scale with the view distance?** Today it does
  (`0.16 * distance`), which is why it stays readable at any zoom. A constructed solid has
  a size in world units and does not. Both are defensible -- a fixed-size arrow is what
  "an object placed at a point" means, and an auto-scaled one is what a viewer's furniture
  wants. Pick one, say why in `.kb/geom.md`, and make the DEFAULT size land on what the
  viewer draws today at a typical distance so the "looks like it does now" requirement is
  met.
- **What happens to `scene:axes`' `:bodies` and `:both` modes.** The report is about the
  origin only. Per-solid triads are a different thing -- they mark a frame, they are drawn
  at every body, and they are sized from the body's own extent. They may well stay lines.
  Decide it; do not change it by accident.
- **How the caller asks for the triad.** Three `geom:arrow`s hung off a node is the honest
  spelling and needs no new viewer surface at all. A convenience that returns the three is
  reasonable if it is a `geom` function returning solids, not a viewer mode.
- **The default of `scene:axes` becomes "no world triad".** That is a behavior change to a
  shipped, documented API. `doc/{en,ja}` and the guide say what it does today, and
  `SceneOffscreenRenderTest` may be resting on the triad being drawn -- check before
  changing the initform.

## Do

- `geom:arrow` in `geom.lisp`, with at least: direction/length, shaft thickness, head
  size, `:sides`, `:color`, `:label`. Winding counter-clockwise like every other primitive
  -- the volume oracle in `GeomLibraryTest` is what catches a flipped facet, and the
  renderer culls by it (`.todo/568` found that the hard way).
- The full new-name checklist from `CLAUDE.md`: `PackageRegistry.GEOM_FUNCTIONS`, a
  `GeomLibraryTest` case (closed-form volume: a cylinder plus a cone is exact), a
  `ci-spec.yaml` cross-backend case, per-name doc pages in `doc/en` AND `doc/ja` with
  byte-identical code fences, `_catalog.yaml`, `functions.md`, and the solid-modeling
  guide. Check `LibraryDefunPruner` still prunes it when unused.
- Retire `scene::%build-axes` and its private buffer if nothing needs it after the
  decisions above, or say in `.kb/geom.md` why it survives.
- The browser twin gets it for free, but "for free" is a claim: run
  `examples/browser/webgl-solids/` and confirm an arrow draws there too.

## Verification

A GUI change, so `CLAUDE.md`'s rule applies: hand-checked on `java -jar`, the native
binary and the compiled outputs. `SceneOffscreenRenderTest` is where the pixel evidence
goes -- `.todo/568` established that a tip pointing the right way and a shaft of the right
thickness are both things an offscreen frame can assert, and `99e63b74` established that
splitting the arithmetic out of the event handler is what makes it testable.
