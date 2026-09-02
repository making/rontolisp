# Signed zero still diverges across backends outside `abs` and unary minus

Difficulty: Medium

Found while fixing the wasm `abs` / unary-minus signed-zero defect (2026-09-02). That
one is closed -- both operators now take `f64.abs` / `f64.neg` on the float branch and
the four backends agree. This item is the RESIDUAL: the same audit, run over every
other float operator that can carry a sign of zero, found ten more rows that do not
agree, and none of them are reachable from the fix that closed the first two.

## The measurement

Every value reaches its operator through a VARIABLE (a `defun` parameter), so no
literal-argument fast path is in play. `nz` is `-0.0`, `pz` is `0.0`, `two` is `2.0`.
Measured on this repository after the `abs` fix, all four backends built from the same
tree; the wasm-GC and component columns were identical on every row, so they are one
column here.

| form | interpreter | JVM | wasm (GC + component) |
| --- | --- | --- | --- |
| `(+ nz nz)` | **`0.0`** | `-0.0` | `-0.0` |
| `(min nz pz)` | `-0.0` | `-0.0` | **`0.0`** |
| `(min pz nz)` | `-0.0` | **`0.0`** | `-0.0` |
| `(max nz pz)` | `0.0` | **`-0.0`** | `0.0` |
| `(max pz nz)` | `0.0` | `0.0` | **`-0.0`** |
| `(signum nz)` | `-0.0` | `-0.0` | **`0.0`** |
| `(sin nz)` | `-0.0` | `-0.0` | **`0.0`** |
| `(tan nz)` | `-0.0` | `-0.0` | **`0.0`** |
| `(mod nz two)` | `-0.0` | `-0.0` | **`0.0`** |
| `(rem nz two)` | `-0.0` | `-0.0` | **`0.0`** |
| `(eql nz pz)` | `NIL` | `NIL` | **`T`** |
| `(equal nz pz)` | `NIL` | `NIL` | **`T`** |

Everything else in the sweep agreed on all four: `abs`, unary minus, binary `+ - * /`
sign propagation, `1/x` on either zero, `sqrt`, `float`, `floor`/`ceiling`/`truncate`/
`round`, `asin`, `atan`, `exp`, `expt`, the comparison and predicate family
(`< > = zerop minusp plusp`), `equalp`, `princ-to-string`, `format ~a`/`~s`, `coerce`
to either float width, and a `#f` array element stored and read back.

## What each row is

- **`(+ nz nz)`** is the only row where the INTERPRETER is the odd one out, and it is
  plainly wrong: IEEE gives `-0.0 + -0.0 = -0.0`, which both compilers produce. The
  interpreter's addition must be folding from an exact `0` identity rather than from
  the first operand.
- **`min` / `max` on a tie** is a THREE-way split, not a two-way one: the interpreter
  answers by SIGN (`min` always `-0.0`, `max` always `0.0` -- `Math.min`/`Math.max`
  semantics), the JVM answers the FIRST argument, wasm answers the SECOND.
  `.kb/linalg-simd.md` already records the variable-path `min`/`max` divergence as
  deliberate ("CL permits either"), but records it as a two-backend one; the
  three-way shape and the interpreter's IEEE-aware behaviour are new.
- **`signum` / `sin` / `tan` / `mod` / `rem`** all preserve the sign of zero as an odd
  function must on the interpreter and the JVM, and flatten it on wasm.
- **`eql` / `equal`** was recorded in `.kb/linalg-simd.md` as UNAUDITED. It is audited
  now: the interpreter and the JVM say `NIL` (CL's reading -- `-0.0` and `0.0` are `=`
  but not `eql`), wasm says `T`, presumably because its `eql` funnels through a numeric
  compare rather than a bit compare for floats.

## What to do

1. Decide the contract per group, and write it into `.kb/` before touching code. The
   defensible one is IEEE everywhere: an odd function preserves the sign of zero,
   `+` follows IEEE, `eql` is a bit compare for floats (CLHS makes `-0.0` and `0.0`
   `=` but leaves `eql` implementation-defined, and both other backends already answer
   `NIL`). `min`/`max` on a tie is the one CL genuinely leaves open -- but three
   different answers in one implementation is not a choice, it is drift.
2. Fix the interpreter's `(+ nz nz)` first: it is a one-backend bug with no ambiguity,
   and it is the only row where the interpreter -- the oracle the other three are
   checked against -- is the wrong one.
3. Then the wasm rows. `signum`/`sin`/`tan`/`mod`/`rem` are each a single emitter;
   `eql` is `WasmEmitHelper`'s float rung.
4. Add the settled rows to `ci-spec.yaml`. The existing `signed-zero-through-a-variable`
   case is the place for them; note that `.kb/linalg-simd.md` currently tells authors
   to keep variable-path `min`/`max` and `eql`/`equal` OUT of `ci-spec.yaml`, so that
   sentence has to move in the same change.

## Acceptance

- The table above reads identically across all four backends, or every remaining row
  is named in `.kb/` as a deliberate, reasoned divergence with the measurement beside
  it -- not as "unaudited".
- No arithmetic result moves: `-0.0` is `=` to `0.0`, so only printed signs and the
  `eql`/`equal` booleans may change.
