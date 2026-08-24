# The packed float array as a Java boundary type

Difficulty: Medium

Filed 2026-08-24 from the `.todo/501` spike. The designator `.todo/503` deliberately left
out, because a measurement decides its shape rather than taste. Read `.kb/vec.md`'s packed
representation section first.

## The measurement that settles it

The JVM backend's packed float array is already a bare `double[]` (or `float[]`) carrying
an embedded `[rank, dim_0..dim_{rank-1}, e_0..e_n]` header, data at offset `1 + rank`
(`JvmFloatArrayRuntimeBuilder`). So the boundary is one header away from being free -- and
what matters is whether a caller pays for that header ONCE or per call:

| | ms/call | vs plain Java | |
|---|---|---|---|
| plain Java loop, C2 auto-vectorized | 0.90 | 1.0x | the thing we are beating |
| `Kernels.NORM2(packed)` on a pre-packed array | **0.27** | **3.3x** | the floor |
| behind an opaque handle (the design below) | **0.27** | **3.3x** | AT the floor |
| behind a facade that copies `double[]` per call | 2.67 | 0.34x | 3x SLOWER than Java |

The copy is ~10x the kernel: **a `double[]`-in/`double[]`-out designator would hand a
caller a slower-than-Java result and call it acceleration.** The handle costs nothing
measurable over the raw packed array -- one `getfield` -- so this item is not a tradeoff
to tune, it is a shape to pick correctly once. Measured 2026-08-24, 2^20 doubles, 300
iterations after 3000 warm-up calls, Oracle GraalVM 25.0.4; handle facade spiked and
reverted.

## The shape to build

A caller has to be able to hold the packed representation across calls. Two candidates,
and they compose:

1. **An opaque handle.** A tiny generated (or embedded, the way the bridges are) final
   class wrapping the packed array -- `RontoVec.of(double[])` copies ONCE,
   `.toArray()` copies out once, `.get(i)`/`.set(i,v)`/`.size()` index in place, and the
   export signature takes and returns the handle. `n` calls cost one copy, not `n`.
   This is the designator `:float-vector` / `:float-matrix` should mean.
2. **Destination-passing exports.** `vec:` already has the `-into` kernels for exactly
   this reason (`.kb/vec.md`), and `--gpu`'s resident tier is the same idea one level
   down. An export whose declared result is a handle the CALLER supplies allocates
   nothing per call, which is what a Java-side loop over a decode step needs.

(1) is already measured at the floor (table above), so the remaining question is not
whether it pays but **where the handle type comes from** -- and that is a real fork:

- **A type emitted per library** (the way the bridges travel, `.kb/template-class-embedding.md`)
  keeps the artifact dependency-free, which is this codebase's house style. But two
  rontolisp libraries then have two incompatible vector types and a caller cannot chain a
  kernel from one into a kernel from the other, which is the first thing anyone will try.
- **A tiny shared `rontolisp-runtime` artifact** holding the handle types. One type,
  chaining works, and it is what every JVM language does (kotlin-stdlib, scala-library).
  It costs the artifact one dependency -- so `.todo/505`'s "the generated pom's
  dependencies is genuinely empty" claim narrows to scalar/string-only exports, and the
  `.todo/506` plugin adds the dependency to the project itself.

**Recommended: the shared runtime**, because interop is worth more than the zero-dependency
property here, and because the alternative that avoids BOTH -- exporting `double[]` and
documenting the packed layout as ABI -- reintroduces exactly the failure mode
`.todo/503` item 5 is about: `norm2(plainArray)` would compile and answer a wrong number.
A boundary whose whole purpose is to stop silent mis-marshalling must not have a
silently-mis-marshallable parameter type. Name the tension in the `.kb/` note either way.

## Things that will bite

- **Two widths.** `double[]` and `float[]` are disjoint representations and the accessors
  dispatch `instanceof double[]` then `instanceof float[]`. A handle has to carry which,
  and `.todo/482`-`487` are adding bfloat16 -- so do not build a two-width assumption in
  (`.todo/483` is the same mistake in a test).
- **Rank.** The header carries it, so a matrix handle is the same class with a different
  `dims`. `vec:matvec` over a rank-2 packed block is the real consumer.
- **`--gpu` residency.** A device-resident array is not its host array
  (`.kb/gpu.md`); a handle that a `--gpu` kernel returns must not force a materialization
  the next call would only re-upload. Check what `.todo/492`/`494` settled for the lazy
  tier before deciding what `.toArray()` does.
- **Mutability.** `.set(i,v)` on a handle a Lisp closure also holds is aliasing across the
  boundary. Say so; do not defensively copy (that is the 8x).

## Acceptance

The designator implemented for both widths and rank 1-2, the handle-vs-floor benchmark
above living in the repo (it is the number that justifies the whole of `.todo/501`), and a
test pinning that a handle held across calls copies once. Docs and a
`.kb/` note stating the aliasing contract, cross-linked from `.kb/vec.md`.
