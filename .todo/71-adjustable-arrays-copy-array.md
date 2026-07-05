# 71: Adjustable / fill-pointer / displaced arrays + `copy-array`

Split off from `.todo/65-cl-utilities-support.md` (cl-utilities stdlib residue,
step 2 -- the HEAVIEST item; likely needs its own session and may itself split
further). Do the lighter todos (66-70) first.

## Goal

The adjustable-array surface cl-utilities `copy-array` touches:

- `make-array` options `:adjustable`, `:fill-pointer`, `:displaced-to`
  (+ `:displaced-index-offset`).
- `(fill-pointer array)` (+ setf), `(array-has-fill-pointer-p array)`,
  `(adjustable-array-p array)`.
- `(array-displacement array)` -- returns the target + offset (two values).
- `copy-array` itself (a cl-utilities function, becomes runnable once the above
  exist).

## Why

cl-utilities `copy-array`. This is genuinely heavy: it changes the array runtime
representation (a fill pointer and a displacement target are extra per-array
state) across all three backends, where arrays are currently a fixed header +
storage (`.kb/linalg.md`, `.kb/no-gc-scalar-wasm.md`). `vector-push`/
`vector-push-extend`/`vector-pop` naturally come with fill pointers and are the
real reason to want this beyond copy-array.

## Current state

- Arrays are rank-n with a fixed header across backends; JVM header slot 0 is an
  `Object[]` of Long dims (`.kb/linalg.md`). No fill pointer, no displacement,
  no adjustable flag today.
- `make-array`/`aref`/`row-major-aref`/`array-dimensions` are the existing
  primitives; `vector`/`svref`/`array-rank`/... are `LispMacroExpander`
  expansions.

## Plan (expect to split)

1. Design the extended array representation first (fill pointer + displacement
   target + adjustable flag) and how each backend carries it WITHOUT breaking the
   existing fixed-header assumptions and the linalg library. Write the design
   into `.kb/` before coding.
2. Sequence: `:fill-pointer` + `fill-pointer`(+setf) + `array-has-fill-pointer-p`
   + `vector-push`/`vector-pop`/`vector-push-extend` (self-contained, high value)
   -> `:adjustable` + `adjustable-array-p` + `adjust-array` -> `:displaced-to` +
   `array-displacement` (hardest; aliasing semantics). Each sub-step is a
   candidate for its own todo.
3. Interpreter -> JVM -> WASM -> ci-spec -> docs per sub-step, as usual.
4. `--no-gc` and the WASM component path are the sharp edges -- gate/limit
   explicitly if displacement is impractical there.

## Acceptance

`copy-array` from cl-utilities runs on the interpreter (at minimum), fill-pointer
vectors work on all four backends, displacement documented (even if
limited/unsupported on `--no-gc`). Native E2E green for whatever ships.
