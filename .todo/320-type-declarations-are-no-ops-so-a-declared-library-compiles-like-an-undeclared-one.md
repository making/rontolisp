# Type declarations are no-ops, so a declared library compiles like an undeclared one

Difficulty: High

`declare` / `declaim` / `proclaim` expand to `nil` and `the` to its value form
(`.kb/declarations-type-checks.md`). Nothing in either compile path reads them. A library
written the way performance-minded CL is written -- chipz declares
`(type (simple-array (unsigned-byte 8) (*)) ...)` on nearly every binding, and
`(type (unsigned-byte 32) ...)` on its accumulators -- therefore compiles exactly like the
same code with the declarations deleted: every `aref`, every `setf aref`, every `+` goes
through the generic boxed path with its run-time kind test.

This is the reason the zlib size-report row cannot approach the C and Zig rows by
squeezing the runtime. **Measured breakdown of the 191,872-byte artifact** (2026-08-11,
`--optimize=size`; function bodies are 183,248 B of it, the data section 8.6 KB):

| group | bytes | share |
| --- | ---: | ---: |
| chipz's own defuns | 86,558 | 47% |
| chipz's lambdas (`labels`/`flet`) | 32,490 | 18% |
| funcall dispatch ladders | 20,456 | 11% |
| spliced prelude runtimes | 15,830 | 9% |
| `FUNC_*` runtime | 14,154 | 8% |
| top-level chunk (constant tables) | 13,760 | 8% |

`.todo/318` and `.todo/319` take roughly 30 KB out of the bottom four rows. What is left
is the top two: **~119 KB of chipz's own compiled code, against 34,484 bytes for the
whole C artifact and 20,072 for Zig.** No amount of runtime narrowing reaches that; the
gap is code density per Lisp form.

## What to investigate

Whether the declarations can drive emission, and what that is worth. Suggested order,
each step measurable on its own:

1. **Measure the ceiling first.** Hand-specialise one hot chipz function (e.g.
   `%inflate-state-machine`'s bit-buffer reads, or `update-window` after 318 lands) into
   the raw packed-array accessors the backend already has, and compare bytes and run
   time. If a hand-specialised version is not dramatically smaller, the premise is wrong
   and this item closes with that finding.
2. **Declared element types on array access.** `aref`/`(setf aref)` over a binding
   declared `(simple-array (unsigned-byte N) (*))` can lower to the same raw path a
   `make-array`-with-literal-element-type site already gets (`.kb/packed-integer-vectors.md`
   has the fused accessors); the declaration is just a second, weaker source for the
   element kind.
3. **Declared fixnum/`(unsigned-byte N)` arithmetic.** The wasm backend already fuses
   integer expression TREES into raw i64 with per-op overflow bailouts
   (`.kb/wasm-int-fusion.md`); a declaration would let the leaf guards go, which is
   exactly what its non-trigger conditions describe.
4. **Where a declaration is WRONG.** CL says a false declaration is undefined behaviour,
   but this project's failures have to stay diagnosable. Decide, and write down, whether
   a declaration-driven path keeps a cheap check (and pays for it) or trusts the
   declaration -- and whether that choice is the same on all four backends. This is the
   real design question, not the lowering.

## Non-goals for a first pass

Full type inference. The declarations are already written in the libraries that matter;
reading them is a much smaller job than inferring what they say, and it is the step that
tells us whether the inference would pay.

## Deliverable

Either a measured reduction in the `zlib` rows of `size-report/results/wasm-flags.md`
with the row's check still gunzipping byte for byte and all four backends agreeing, or a
recorded finding in `.kb/declarations-type-checks.md` explaining what the ceiling
measurement showed and why the declarations are not worth reading -- with the numbers, so
the next visitor does not re-measure.
