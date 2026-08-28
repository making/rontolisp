# 556. A promoted top-level global re-boxes into a static field on every assignment, so a `defparameter` accumulator costs 12.7 ns a step

Difficulty: High (the `RawLocal` dual representation lifted from JVM local
slots to CLASS FIELDS, which drags in `defvar`/`defparameter`, dynamic
binding, the eval mirror, `jvm-export`, the tree shaker and `--dynamic` --
`.todo/412`'s machinery again, against a wider blast radius)

Found while finishing `.todo/534` (2026-08-28, this machine): with the fusion
pass's `random` and `aref` leaves in, this is what is LEFT in both of
`.todo/517`'s top-level rows, and it is the larger half of each.

## The defect

A top-level `(defparameter s 0)` compiles to a static `Object` field `_g$S`, so
`(setq s (+ s <int>))` in a loop is: `getstatic`, a fused `_fx$N` that unboxes,
computes raw and `Long.valueOf`s the result, `putstatic`. The box ESCAPES into
a static field, so escape analysis -- which removes every other `_fx$N` return
box in the same loop -- cannot remove this one, and the store pays a GC write
barrier on top. The identical loop over a `let` local takes the `RawLocal`
dual representation (`.kb/jvm-int-fusion.md`) and allocates nothing.

The `_top$0` of `.todo/517`'s `random-toplevel.lisp`, after `.todo/534`:

```
43: getstatic     _g$S              <- the boxed global
46: invokestatic  _fx$23            <- draws raw, adds raw, re-boxes
49: dup
50: putstatic     _g$S              <- escapes; EA cannot help
```

## The measurement (10^7 iterations, compute only, seconds)

`(setq s (+ s 1))` in a `dotimes`, the accumulator spelled two ways, best of 7
with the 0.075 s JVM startup subtracted:

| accumulator | compute | per assignment |
| --- | --- | --- |
| `(let ((s 0)) ...)` -- `RawLocal` | 0.033 | 3.3 ns |
| `(defparameter s 0)` -- static field | 0.160 | **16.0 ns** |

12.7 ns of representation, for the same arithmetic. What that costs the two
rows `.todo/534` closed (compute only, after 534):

| row | top-level spelling | defun spelling | of which this defect |
| --- | --- | --- | --- |
| 10^7 x `random` | 0.204 | 0.072 | ~0.13 |
| 10^7 x `aref` | 0.351 | 0.178 | ~0.13 |

So the top-level spelling of both rows is ~2/3 this defect and ~1/3 everything
else, while the defun spelling of the same program is already within 2x of
hand-written primitive Java.

## What to build

The `RawLocal` triple (raw `long` slot, boxed shadow, `int` flag) as CLASS
FIELDS for a promoted top-level global that qualifies: assigned an
integer-shaped value, never dynamically bound, not exported, not reachable
from `eval`. `JvmSetqCompiler` already funnels every assignment through
`JvmIntFusionCompiler.compileRawStore`, and `compileSymbolRef` already knows
how to read a dual representation -- the work is deciding eligibility across
the seams a local does not have:

- `defvar`/`defparameter` idempotence and the compile-time `definedGlobals`
  tracking.
- Dynamic binding (`let` of a special) and `--dynamic`, which must keep
  observing redefinition.
- The eval mirror (`_store` into `_genv`): a raw global's value has to reach
  an `eval`'d form correctly, which today's mirror does by holding the box.
- `rontolisp:jvm-export` handles and the `.kb/jvm-export.md` boundary types.
- The tree shaker and the byte-identity contracts: a program with no eligible
  global must emit exactly what it emits today.

Check whether the same shape wants a raw-RETURNING `_fx$N` variant (`(...)J`
plus a separate bail protocol) while the store path is being rebuilt -- that
is re-evaluation trigger 1 in `.kb/jvm-int-fusion.md`, and the two changes
touch the same dispatch.

## Acceptance

- No `Long.valueOf` and no `putstatic` of a fresh box in the hot loop of
  `.todo/517`'s `random-toplevel.lisp` / `aref-toplevel.lisp` `_top$0`.
- The top-level spelling of both rows lands within 2x of hand-written
  primitive Java, i.e. where the defun spelling already is.
- `(let ((s 0)) ...)` and `(defparameter s 0)` accumulators cost the same per
  assignment, on the same program.
- Every seam above keeps its behavior: a dynamically bound special, an
  `eval`'d read of an assigned global, a `jvm-export` handle, `--dynamic`, and
  a program with no eligible global compiling byte-identically.
- All four backends stay output-identical.
