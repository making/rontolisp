# 109 — Named element-wise math functions (numpy ufunc parity) for `linalg:` and `vec:`

**Status: NOT STARTED (filed 2026-07-10, from the todo-107 lane-form review).**

## Why

`linalg:emap` cannot be accelerated: it `funcall`s an arbitrary Lisp callback per element,
so every element pays a boxed call boundary regardless of `--simd`. The fix is not to
speed up `emap` but to make it unnecessary for the common cases: **give each frequent
per-element operation a name, then intercept the name** with the todo-107 machinery.
Today `(linalg:emap #'exp a)` is the only way to exponentiate an array; a named
`linalg:exp` can be a de-boxed kernel. Goal: cover as much of numpy's ufunc surface as
the scalar builtins allow, in BOTH packages (`linalg:` = permissive/broadcasting,
`vec:` = strict packed-only contract, usable on `--no-gc`).

## The recipe (per function — established pattern, no new design needed)

1. **Semantics for free**: add a one-line defun to `linalg.lisp`
   (`(defun linalg:exp (a) (linalg:emap (function exp) a))`) and a plain do-loop defun to
   `vec.lisp`. Works on every backend immediately, `--simd` or not; the Lisp source stays
   the single source of truth. Docs: per-operator pages (en+ja) + `_catalog.yaml` + a row
   in the curated `reference/functions.md` tables (the step that keeps getting missed).
2. **`--simd` interception**: per backend, a de-boxed element loop that calls the SAME
   scalar implementation the defun would call (`Math.exp` on interpreter/JVM, the
   `WasmExpCompiler` software approximation on wasm) — read widened to f64, compute,
   store once (narrowing at `#f` width exactly as `emap`'s write does). Bit-identity to
   the own-backend defun is then constructive; `assertLinalgMatchesTheScalarPath` /
   the vec byte-identity tests pin it. `linalg:` kernels use the declined-input null
   protocol; `vec:` kernels stay total (trap on non-packed).
3. **True lane forms ONLY where bit-safe**:
   - `sqrt` / `abs`: wasm has `f64x2/f32x4.sqrt/abs`; sqrt is correctly rounded, so the
     `#f` widen-compute-narrow round trip is exact (the same 53 >= 2*24+2 bound as
     add/mul). Lane forms are bit-identical — do them.
   - `exp` / `log` / trig: NO v128 instruction, and the JVM Vector API's
     `VectorOperators.EXP` is not bit-identical to `Math.exp` — do NOT lane-ize; the
     de-boxed scalar loop is the ceiling (still removes the boxed funcall per element).

## Candidates, phased

**Phase 1 — the scalar builtin already works on every backend** (interpreter, JVM,
wasm-GC; `vec:` versions also on `--no-gc`):

| new function | scalar impl | notes |
|---|---|---|
| `exp` | `exp` | silu/sigmoid/softmax heart; wasm exp is a software approx, so its low digits already differ across backends (pre-existing; keep out of ci-spec) |
| `sqrt` | `sqrt` | lane form bit-safe (see above) |
| `abs` | `abs` | lane form bit-safe |
| `square` | `(* x x)` | = `linalg:mul a a` internally; numpy parity name |
| `negative` | `(- x)` | numpy `np.negative`; trivial lane form (sub from 0 / xor sign) — verify -0.0 per todo-108 first |
| `sign` | `signum` | wasm-supported (`WasmSignumCompiler`); mind `signum`'s CL float/-0.0 edges |
| `reciprocal` | `(/ 1 x)` | rides the existing div kernels |

**Phase 2 — blocked on wasm scalar support**: `log`, `sin`, `cos`, `tan`, `tanh`,
`sinh`, `cosh`, `asin`, `acos`, `atan` are in `BuiltinFunctionWrappers.WASM_UNSUPPORTED`
(no WASM instruction, no software impl). Prerequisite: software approximations in the
wasm backend, `WasmExpCompiler`-style (argument reduction + polynomial; document the
cross-backend low-digit divergence like exp's). Until then these can ship as
interpreter/JVM-only... which contradicts "every function behaves identically
everywhere" — so prefer doing the wasm prerequisite first, or gate the whole phase.
`tanh` is the highest-value member (ML activations).

**Phase 3 — binary/comparison ufuncs, todo-108 territory (design care needed)**:

- `maximum` / `minimum` (element-wise binary), `clip`, `relu` (= max with 0): wasm has
  `f64x2.min/max` but its NaN/-0.0 semantics differ from Java `Math.max` and from a
  comparison-based defun. Define the oracle in Lisp via `>` (now IEEE on all backends,
  todo-108) and make each kernel mirror the comparison, not the lane min/max — or prove
  the lane op identical on the values the defun produces. Same trap `amax` documented.
- `power` (element-wise `expt`): CL `expt` has exact integer/ratio semantics; decide
  whether `linalg:power` is float-only (numpy-like) before touching it.
- `floor` / `ceil` / `trunc` / `round` element-wise: numpy returns FLOATS; CL `floor`
  returns integers. Needs a semantics decision (float-valued like `ffloor`?) — do not
  overload the CL names.

**Maybe, as fused named ops** (norm precedent: fusion is fine when values match the
defun): `sigmoid`, `silu`, `softmax` (exp + sum + div; decide whether the defun does the
max-subtraction stabilization — whatever the defun says becomes the contract), `relu`.

## Constraints to carry over (all established)

- Per-backend bit-identity to the scalar defun; `#f` unary ops compute in f64 and narrow
  once (the emap rule) unless the op is in the correctly-rounded class (sqrt/abs).
- `linalg:` decline protocol (null sentinel; anything odd runs the defun). `vec:` strict.
- wasm kernel additions shift function indices: follow the `linalgFuncBase()` /
  `userFuncBase()` bookkeeping and UPDATE (never weaken)
  `simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks`; a build without
  `--simd` must stay byte-identical.
- Docs en+ja mirrored in the same commit; the `--simd` guide's intercepted-function list
  and the linalg guide's "SIMD acceleration" section must be extended in sync (and the
  vec/linalg choosing table if the `-into` story changes).
- Interpreter kernels live behind `LinalgSimd`/`VecSimd` only (the `Target_*` Web Image
  substitutions must keep cutting them out — `./mvnw -Pweb compile` is the local check).
