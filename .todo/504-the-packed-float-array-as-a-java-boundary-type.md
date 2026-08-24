# The packed float array as a Java boundary type

Difficulty: Medium

Filed 2026-08-24 from the `.todo/501` spike. The designator `.todo/503` deliberately left
out, because a measurement decides its shape rather than taste. Read `.kb/vec.md`'s packed
representation section first.

## The measurement that settles it

The JVM backend's packed float array is already a bare `double[]` (or `float[]`) carrying
an embedded `[rank, dim_0..dim_{rank-1}, e_0..e_n]` header, data at offset `1 + rank`
(`JvmFloatArrayRuntimeBuilder`). So the boundary is one header away from being free. What
it costs to pay that header per call, 2^20 doubles, 200 iterations, Oracle GraalVM 25.0.4:

| | ms/call | |
|---|---|---|
| plain Java loop, C2 auto-vectorized | 0.90 | the thing we are beating |
| `Kernels.NORM2(packed)` on a pre-packed array | **0.30** | 3x faster |
| the same behind a facade that copies `double[]` in | 2.5-2.9 | 3x SLOWER |

The copy is 8x the kernel. **A `double[]`-in/`double[]`-out designator would hand a caller
a slower-than-Java result and call it acceleration.** That is the entire content of this
item.

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

Decide against the numbers, not the aesthetics: measure (1) alone, then (1)+(2), against
the 0.30 ms pre-packed floor above. If a handle cannot get within ~10% of it, the design
is wrong and the answer is a raw-packed escape hatch instead.

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

The designator implemented for both widths and rank 1-2, a benchmark in the repo showing
the handle within measuring distance of the 0.30 ms pre-packed floor for a same-shaped
call, and a test pinning that a handle held across calls copies once. Docs and a
`.kb/` note stating the aliasing contract, cross-linked from `.kb/vec.md`.
