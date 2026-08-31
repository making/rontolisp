# A defclass and a defun of the same name keep each other alive in every program

Difficulty: Medium

Measured 2026-08-31 while gating the JVM geom kernels. `LibraryDefunPruner` keys a
definition by NAME, and `geom:bounds` is the one name in `geom.lisp` that is BOTH a
`defclass` and a `defun`. The class form is a root that spells its own name, so the
defun `geom:bounds` is always live, which keeps `geom::%solid-bounds`, which keeps
`geom::%vertex-extremes` and (through `geom:bounds-union`) a little more.

The effect is that **every** program that splices geom carries them, including one that
touches no solid at all:

```
$ ... prune (print (geom:vec3 1 2 3))
GEOM:VEC3 GEOM::%UNIT GEOM::%IDENTITY-ROTATION GEOM:AXIS-VECTOR GEOM:AXIS-ANGLE-MATRIX
GEOM:RPY-MATRIX GEOM:MAKE-TRANSFORM GEOM:COMPOSE GEOM:WORLD-TRANSFORM GEOM:FACETS-OF
GEOM::%VERTEX-EXTREMES GEOM::%SOLID-BOUNDS GEOM:BOUNDS GEOM:BOUNDS-UNION
```

The last four are the ones that should not be there. `.kb/geom.md`'s "Pruning" section
said a `(print (geom:volume (geom:box 10)))` class carries "none of ... bounds"; it
carries all four, and that file now records the correction.

## Why it is worth an item rather than a shrug

- It is dead weight in every geom artifact on all three compile backends -- four defuns
  plus whatever `bounds-union` reaches, in a browser `.wasm` as much as in a `.class`.
- **It cost a design decision.** todo-599's JVM kernel bridge wanted to arm on
  `geom::%vertex-extremes` (the member behind `geom:bounds` and `geom::%model-extent`)
  and could not: a gate naming it would fire for every geom program, which is exactly
  the "gate on the splice" the item forbade. So the gate is the other three members and
  `%vertex-extremes` rides along, and a program that reads a PLY and only asks for
  `geom:bounds` gets no acceleration at all. Fixing this lets that gate name all four.
- The mechanism is general: any library that names a class and a function the same way
  pays it, and nothing warns.

## What to do

1. **Confirm the mechanism first.** The reading above is from the surviving-defun list,
   not from the pruner's own trace. `LibraryDefunPruner`'s `Candidates` says
   `defclass`/`define-condition`/`defstruct`/`defgeneric` forms are KEYED (kept iff a
   defined name is referenced), and `.kb/geom.md` says geom's four `defclass` forms are
   unkeyed roots -- those two statements cannot both be true of the same form, and which
   one holds decides the fix. Instrument the fixpoint and find out which reference makes
   `GEOM:BOUNDS` live.
2. Separate the two namespaces in the pruner's keys, the way the language already does
   (`.kb/lisp2-namespaces.md`): a reference in FUNCTION position keeps the defun, a
   reference in a type/`make-instance`/specializer position keeps the class. A single
   string key cannot tell them apart and that is the bug.
3. The blast radius is every library and all three compile backends, so the pin is a
   size/contents measurement before and after on `(print (geom:vec3 1 2 3))`,
   `(print (geom:volume (geom:box 10)))`, `examples/browser/webgl-solids/solids.wasm`
   and a torch program, plus the whole `ci-spec` corpus -- a program that loses a defun
   it actually reached fails at run time, not at compile time.
4. Re-measure `.kb/geom.md`'s "Pruning" section and todo-599's gate afterwards, and move
   `geom::%vertex-extremes` into `JvmGeomKernelCompiler.gateMembers()` if it can go.
