# Hiragana demo: free-handwriting generalization ceiling

**Status:** investigated, changes reverted (kept committed `416f2ad`
multi-font+warp version). This records what was tried, why it was reverted, the
real path forward, and a JVM gotcha found along the way, so the work is
recoverable.

## Problem
The committed demo recognizes input drawn close to the font reference very well
(measured: に 0.998 / Klee 0.998 / +8deg 0.85, あ 0.999 / Klee 1.000, ま 1.000,
す/さ/け/も 0.92-1.0). But genuinely free handwriting that deviates structurally
misses. Two reported user samples:
- A drawn あ with a **low crossbar + long vertical stem + centred loop** -> read
  as さ, then も after more augmentation. Font あ always has a high crossbar.
- A drawn に with a **hooked left radical + two disconnected, diagonally slanted
  right strokes** -> read as い, then re, then ke. Font に has a straight radical
  and a connected right side.

## What was tried (and the conclusion)
- **Capacity is NOT the bottleneck.** hidden 20 -> 64 (576-64-46) trained to
  train-acc 1.0 but did not improve either sample (あ still さ); and hidden>20
  breaks the JVM infer path (see `.todo/17`). Reverted.
- **Augmentation is whack-a-mole past a point.** Added (then reverted) two more
  augmenters on top of the committed dilate+shift+affine-warp+multi-font:
  - `scale-image (flat sx sy)` -- independent x/y scale about centre (aspect
    jitter), for proportion differences.
  - `elastic-image (flat ax ay px py)` -- cheap elastic deformation via a smooth
    sinusoidal displacement field: `dx = ax*sin(0.4*y+px)`, `dy = ay*sin(0.4*x+py)`,
    nearest-neighbour resample. Non-rigid wobble.
  build-dataset grew to ~4968 samples (added 3 scale + 4 elastic, each also
  thickened, per font variant). train-acc 0.9996, **templates still 46/46**, and
  **no regression on reference-style input** -- but the two freehand samples were
  still wrong, just with the error shuffled to different classes (あ->も,
  に->ke). It widens the manifold slightly without catching structurally-off
  handwriting.

**Conclusion:** synthetic-font templates + a tiny MLP + affine/elastic
augmentation has a ceiling. It is strong for "draw close to the reference" and
cannot reliably recognize arbitrary free handwriting (different proportions,
stroke connectivity, per-stroke slant). This is inherent to the data, not the
model size.

## Real path forward (the only thing that fundamentally fixes it)
Train on **real handwriting data** (e.g. ETL8B/ETL9B, or Kuzushiji-49). This is
a different project and collides with three current design choices:
- Loading a real dataset in rontolisp is impractical (huge Lisp lists, no binary
  parsing) -> needs external preprocessing.
- Would want more capacity -> blocked by the JVM baked-constant ceiling
  (`.todo/17`); fixing that (packed-data/quantized weights) is a prerequisite.
- External training + baking breaks the "trained in Lisp / self-contained /
  all-four-backends" story the demo is built around.
A lighter middle ground: collect a handful of real handwritten samples per kana
and mix them into the synthetic set -- cheaper, partial improvement, still in-Lisp.

## JVM gotcha found here (relevant when re-adding the augmenters)
On the JVM backend, array indices derived from floats are fragile:
- `round` of a value **bound to a variable** yields an integer usable as an aref
  index; `round` of an inline complex expression, and `floor`/`truncate` in a
  compound index like `(aref v (+ (* iy w) ix))`, can leave a `double` and throw
  `ClassCastException: Double cannot be cast to Long` at `_aref1`.
- Safe pattern (used to make warp/scale/elastic work): bind each float source
  coordinate to a `let*` variable, then `round` the variable:
  `(let* ((dx (* ax (sin ...))) (ix (+ x (round dx)))) ...)`.
- This looks like a real codegen inconsistency in how `round`/`floor`/`truncate`
  result types flow through `+`/`*` into array-index position. Worth a separate
  look in `JvmRoundCompiler`/`JvmExprCompiler` arithmetic typing if we want
  float-indexed array math to "just work".

## References
- `examples/hiragana/train.lisp` (augmentation + `build-dataset`),
  `common.lisp` (forward pass), `glyphgen/GlyphGen.java` (`FONTS`).
- `.todo/16` (full-set status), `.todo/17` (JVM baked-constant ceiling).
