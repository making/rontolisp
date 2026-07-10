# 109 — Named element-wise math functions (numpy ufunc parity) for `linalg:` and `vec:`

**Status: PHASE 1 + 1.5 DONE; PHASE 2 PARTIAL — `log` + `tanh` DONE (2026-07-10, first
release), `sin`/`cos`/`tan` + `asin`/`acos`/`atan`/`sinh`/`cosh` remain (second release).
Phase 3 not started.**

## Phase 2 first release — `log` + `tanh` (DONE 2026-07-10)

The prerequisite WASM software implementations landed first, then the ufunc layer rode
the Phase 1 recipe unchanged:

- **Scalar builtins**: `WasmLogCompiler` (exponent extraction via
  `i64.reinterpret_f64` + bit ops — `WasmWriter` gained a 64-bit `writeSignedLeb128(long)`
  for the mantissa-mask `i64.const`s — mantissa normalized into `(sqrt2/2, sqrt2]`, atanh
  series `2s(1 + u/3 + ... + u^5/11)`, ~1e-10 relative; denormals pre-scaled by 2^54;
  NaN/±0/negative/+inf branches match `Math.log`) and `WasmTanhCompiler`
  (`(e^(2x)-1)/(e^(2x)+1)` over the shared `WasmExpCompiler.emitExpCore`, the doubled
  argument clamped to ±40 so large inputs saturate to exactly ±1.0 branch-free;
  `(tanh -0.0)` = `0.0`, the signum-class edge; tiny-x cancellation ~1e-8 relative,
  documented). Both removed from `BuiltinFunctionWrappers.WASM_UNSUPPORTED`, so
  `#'log`/`#'tanh` and the compiled `eval` work; constants package-private for the
  kernel mirrors. ci-spec gained `log-tanh-exact-cross-backend-cases` (log(1), tanh(0),
  tanh(±25) — integer-exact only).
- **Defuns**: `linalg:log`/`linalg:tanh` (named emaps), `vec:log`/`vec:tanh` +
  `-into` siblings; PackageRegistry exports, LispNames constants.
- **`--simd`**: interpreter (`VecSimdKernels.logInto`/`tanhInto` + F variants,
  `LinalgSimdKernels.log`/`tanh`), JVM (`UOP_LOG`/`UOP_TANH`, `hasLaneForm` gate — NO
  lane forms anywhere, de-boxed scalar loops are the ceiling), wasm-GC
  (`WasmVecSimdRuntimeBuilder` LOG/TANH/LOG_INTO/TANH_INTO, FUNC_COUNT 27 → 31;
  `WasmLinalgSimdRuntimeBuilder` LOG/TANH, FUNC_COUNT 20 → 22; `userFuncBase()` shift
  47 → 53; raw-f64 emitters `emitLogF64`/`emitTanhF64` mirror the boxed compilers'
  exact op order, dispatched via `SCALAR_OP_*`/`emitScalarUnaryF64`), `--no-gc`
  (`compileSimdUnaryF64` generalized from the `isExp` boolean to the `SCALAR_OP_*`
  selector; both lowerings emit the identical element loop).
- Docs en+ja: log/sinh-cosh-tanh/math-backends updated, `linalg-log`/`linalg-tanh`
  pages + catalog + functions.md rows, simd guide lists + `-into` table (nine unary
  ufuncs), linear-algebra ufunc list. `.kb/vec.md`/`.kb/linalg-simd.md` counts updated.

## Phase 2 second release — the remaining trig/hyperbolic (NOT STARTED)

`sin`/`cos`/`tan` need real range reduction (payne-hanek-lite or argument folding over
π/2 quadrants) — deliberately deferred as the design-heavy half. `asin`/`acos`/`atan`
need their own series/reductions; `sinh`/`cosh` could derive from the software exp like
tanh (mind overflow at |x| > ~350 where e^x overflows but sinh doesn't... it does too at
710; the real issue is exp's Taylor accuracy degrading for |t| > ~1, i.e. |x| > 256) but
were deferred with them. All eight stay in `BuiltinFunctionWrappers.WASM_UNSUPPORTED`
and interpreter/JVM-only; do NOT add their ufuncs until the wasm scalar exists (the
"every function behaves identically everywhere" principle).

Phase 1 shipped `exp`/`sqrt`/`abs`/`square`/`negative`/`sign`/`reciprocal` in BOTH
packages (each `vec:` member with its `-into` sibling), intercepted under `--simd` on
the interpreter / JVM / wasm-GC and lowered natively on `--no-gc`. Decisions taken:

- `linalg:square` = `(linalg:mul a a)`, `linalg:reciprocal` = `(linalg:div 1 a)`,
  `vec:square` = `(vec:mul v v)`: transitively accelerated, NO new kernels for them
  (the interception guards pin them as `#<lambda>`).
- `--no-gc` = decision (b): `vec:exp` / `vec:sign` (+`-into`) were clear compile errors
  (`SIMD_NO_SCALAR_IMPL_NO_GC`) -- SUPERSEDED by Phase 1.5 below, which lowers them
  natively; the five arithmetic ufuncs lower natively (scalar loops by default, v128
  under `--simd`, `WasmVecLoops.simdMap1`/`scalarMap1`).
- Per-backend bit-identity holds against each backend's OWN defun: the wasm kernels
  mirror wasm's `0 - x` unary minus / `x < 0 ? 0 - x : x` abs / `(x>0)-(x<0)` signum /
  software `exp` (todo-108 residual edges), so `-0.0`/NaN/exp cross-backend output
  stays out of ci-spec (which was not touched).
- wasm vec block FUNC_COUNT 15 -> 27, linalg block 15 -> 20, `userFuncBase()` shift 47;
  new opcodes (`f32x4/f64x2.sqrt/abs/neg/lt`, `v128.bitselect`) in `Instruction` +
  `WasmTreeShaker.skipSimd`. Full design record: `.kb/vec.md` (todo-109 section),
  `.kb/linalg-simd.md`.

## Phase 1.5 — flip `--no-gc` exp/sign from decision (b) to (a) — DONE (2026-07-10)

`vec:exp` / `vec:sign` (+`-into`) now lower natively on `--no-gc` instead of erroring.
As shipped:

- `NoGcWasmCompiler`: the four names moved from `SIMD_NO_SCALAR_IMPL_NO_GC` (set +
  `requireKnownSimd` branch deleted) into `SIMD_MEMBERS`, joined the `typeOfSimd`
  operandWidth case, and compile via the new private `compileSimdUnaryF64(args, fn,
  isExp, into, what)` -- the same dst/vp/dp/count setup as `compileSimdUnary`, then a
  one-element-per-iteration loop (WasmVecLoops `openScalarCountLoop`/`advancePtr`/
  `closeLoop`) whose body is `f64.load` (f32: `f32.load` + promote) ->
  `WasmVecSimdRuntimeBuilder.emitExpF64(w, t, acc)` / `emitSignumF64(w, t)` -> store
  (f32: demote + `f32.store`). BOTH `--simd` modes emit this identical loop (exp has
  no lane form anywhere; sign's is not worth one) -- no `0xFD`. Two f64 `Fn` locals
  (t + acc for exp, t only for sign). `out` may alias `v` (the add-into rule).
- Semantics: `--no-gc` exp/sign mirror the WASM family's own edges (software exp low
  digits; sign maps -0.0/NaN to 0.0) -- consistent with wasm-GC, divergent from
  interpreter/JVM at those edges (pre-existing; kept out of ci-spec).
- Scope decision: `vec:`-only. The SCALAR `(exp x)` / `(signum x)` builtins remain
  unknown on `--no-gc` (the same emitters would serve if ever wanted).
- Tests: `NoGcWasmCompilerTest.expAndSignAreClearCompileErrorsOnNoGc` flipped to
  `expAndSignLowerNativelyOnNoGc` (INV_SCALE `f64.const` present, no `0xFD` in either
  mode, `-into` skips the bump allocator);
  `WasmLispCompilerIntegrationTest.noGcRunsExpAndSignUnderBothLowerings` compares the
  nontrivial exp probes against a wasm-GC run (not a hardcoded constant) and pins the
  exact sign/exp(0) values at both widths under both lowerings.
- Docs: simd-acceleration guide (en+ja, API paragraph + `--no-gc` bullet) now say all
  seven ufuncs work everywhere; `.kb/vec.md` todo-109 section + layer 2 updated.

**Status of the original plan below: Phase 1 text kept for reference; Phases 2/3 NOT
STARTED (filed 2026-07-10, from the todo-107 lane-form review).**

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
   **Every new `vec:` member also gets its `-into` sibling** (`vec:exp-into out v`, ...):
   destination-first (CL `map-into` order), returns the destination, and — these being
   element-wise unary — `out` MAY alias the operand (element i depends only on element i,
   the `add-into` rule, NOT the `matvec-into` one). Same contract as the existing five:
   same width required, `out` at least as long, length unchecked. The alias-permission
   comment lives in three places for the binary kernels (vec.lisp / VecSimd /
   JvmSimdVectorTemplate) because each accelerated site replaces the defun — keep that
   discipline. `-into` stays a `vec:`-only concept; `linalg:` remains value-oriented
   (that split is now user-documented in the choosing table — don't blur it).
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

**Phase 1 — the scalar builtin already works on interpreter / JVM / wasm-GC.** `vec:`
versions (each with its `-into` sibling) also target `--no-gc`, with one verified caveat:
`NoGcWasmCompiler` has NO `exp` and NO `signum` case (grep confirmed 2026-07-10), so
`vec:exp` / `vec:sign` on `--no-gc` need either a scalar lowering added there (the exp
software approximation exists only in the GC backend's `WasmExpCompiler`) or a documented
per-function unavailability, the `vec:matvec` / `from-list` precedent. Decide early;
the arithmetic-only members (`sqrt`/`abs`/`square`/`negative`/`reciprocal`) have no such
problem:

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
- Docs en+ja mirrored in the same commit; the `--simd` guide's intercepted-function list,
  its destination-passing table (which gains the new `-into` rows) and the linalg guide's
  "SIMD acceleration" section must be extended in sync (and the vec/linalg choosing table
  if the `-into` story changes).
- Both WASM `--simd` paths lower `-into` by threading `boolean into` through the SAME
  kernel bodies (skip the destination allocation, write into the caller's) — reuse that
  seam for the unary kernels rather than inventing a second one.
- Interpreter kernels live behind `LinalgSimd`/`VecSimd` only (the `Target_*` Web Image
  substitutions must keep cutting them out — `./mvnw -Pweb compile` is the local check).
