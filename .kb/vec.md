# vec: package + packed float-array acceleration

`vec:` is a set of portable packed-float vector kernels defined as plain defuns in
`src/main/resources/am/ik/rontolisp/eval/vec.lisp` -- the cross-backend ORACLE -- and intercepted by
four acceleration layers, each of which must match its own backend's defun bit for bit.

## The type it rides on

A vector is a rank-1 packed `(array double-float)` (`#d(...)`, `make-array :element-type
'double-float`), so generic `aref`/`length`/`make-array` interoperate. Storing a non-real is a type
error; no boxed fallback. `#f(...)` is a DIFFERENT width, and `vec:` is WIDTH-POLYMORPHIC:
element-wise kernels preserve the input width (`vec::%make-like`), reductions fold to an f64 scalar.

- Interpreter: the sealed `LispFloatArray` (`LispDoubleFloatArray`, `LispSingleFloatArray`,
  `LispBFloat16Array`, `.kb/bfloat16.md`); no in-array header.
- JVM: a bare primitive array with an embedded header whose LAYOUT IS WIDTH-DEPENDENT, owned by
  `codegen/jvm/JvmPackedFloatWidth`: `double[]`/`float[]` are `[rank, dim..., data...]`, bfloat16
  `short[]` is `[rank, hi_0, lo_0, ..., data...]` (a `short` cannot hold a dimension above 32767).
  **No emitter spells the offset itself** -- a hard-coded `1 + rank` reads a length-1 bfloat16
  array's rank word as its element, silently.
- wasm-GC: `TYPE_FARRAY` whose data field holds `$f64arr`/`$f32arr` -- except under `--simd`, where
  it holds a `TYPE_VBLOCK` (layer 3); the field is `(ref null eq)` either way.
- `--no-gc`: `[count:i32][data]` in linear memory (`Ty.F64VEC`/`F32VEC`); rank-2 is a distinct
  pointer kind `Ty.F64MAT`/`F32MAT`, `[rows][cols][row-major data]`.
- A JAVA boundary type too: `rontolisp:jvm-export`'s `:float-vector`/`:float-matrix` hand over a
  `am.ik.rontolisp.runtime.RontoFloatArray` that ALIASES it (copying measured ~10x the kernel),
  [jvm-export.md](jvm-export.md).
- **`coerce` and `concatenate` build one too** from a result-type designator naming the width,
  through the shared `%seq-float-vector` helper
  ([concatenate-result-families.md](concatenate-result-families.md));
  `eval/PackedFloatReachabilityTest` walks the permits through that door.

## Asking a packed array its width

`LispFloatArray` is sealed, so EVERY width test is an exhaustive `switch` over it with NO `default`
arm -- never `instanceof LispSingleFloatArray` read negatively as "therefore double", never a cast
straight to one record. MEASURE the site count (add a throwaway third permit, `./mvnw compile`),
never count by hand.

- **The rule is not "no `default`" -- an arm matching more than one permit IS a default however
  spelled.** `NoGcWasmCompiler.typeOf`'s `case LispFloatArray ignored -> Ty.F64VEC` emitted bfloat16
  literals at eight bytes an element. A supertype pattern is correct exactly when the arm's answer
  is width-INDEPENDENT (`arrayp`, rank, total size, a no-op walk arm).
- **Exhaustiveness is checked in the STATEMENT form for the sealed type, and only in the EXPRESSION
  form for the width enum** (JLS 14.11.2). Write a `FloatWidth` switch as an EXPRESSION.
- `FloatWidth` and the permits are in bijection (`FloatWidthTest`), which catches a CONSTANT added
  ahead of its permit. Switch over `LispFloatArray.width()` only for the width as a VALUE (the
  `%la-gather-strided` wire, which `%la-dropout-mask` also rides as `FloatWidth.code()`; a boxed
  `Long 0` passes a nullness test, so grep the ARITY of a member on this wire, never its name).
- Deliberately still `instanceof`: a same-width guard inside an arm; the must-be-double
  requirements (`%la-adam-step`, `%la-rng-fill`, `%la-dropout-mask`); `floats`/`doubles` in the
  linalg kernels; `eval/PackedBuffer.of`, `eval/GeomKernels`.
- **The one width test that cannot be a compile error** is NAME -> width: a new width's name is new
  source text. It is DERIVED: `LispFloatArray.widths()` holds one zero-length prototype per permit
  and `LispFloatArray.prototypeFor` resolves a `:element-type` designator by matching the permits'
  own `elementType()`, so the name `make-array` accepts is by construction the name
  `array-element-type` reports. Every consumer switches over what `prototypeFor` answers with no
  `default`. `eval/PackedFloatReachabilityTest` pins the table against `getPermittedSubclasses()`
  and walks every permit through every door (the exhaustive-switch net's loud half). A door loop
  must iterate the PERMITS, never `WIDTHS` itself: a missing row would shrink the loop silently.

## vec.lisp -- the scalar reference

`VecLibrary` splices/loads it like `LinalgLibrary` (interpreter: lazy on the first `vec:`
resolution; compile path: `CompileFrontend.expand`). `--no-gc` is gated OFF the splice and
intercepts the surface natively.

- `vec:+`/`-`/`*`/`/` are STRICTLY BINARY aliases of `add`/`sub`/`mul`/`div`: every `vec:` kernel is
  fixed-arity and allocation-explicit, and `--no-gc` has no cons list to fold over.
- `from-list`/`to-list` need cons lists -- a `--no-gc` compile error.
- `mean`/`norm` are never intercepted; they accelerate through `sum`/`dot` (so `square` through
  `mul`). `#'vec:dot` names the scalar defun on every backend.
- `matvec` is the ONE `vec:` member `--gpu` intercepts (allocating form, above 2^17 elements, over a
  resident unwritten matrix, accumulating in DOUBLE like the defun; `.kb/gpu.md`).
  `matvec`/`matvec-into` are the only members `--parallel` splits, bit-identically
  (`.kb/simd-parallel.md`).

## Destination-passing `-into` kernels

Every vector-returning kernel has an `-into` sibling writing into a caller-supplied destination
(argument 1, CL's `map-into` order) and RETURNING it; reductions have none. Only `--no-gc` needs it
for correctness: it bump-allocates with NO FREE (the arena pops at an export boundary, never within
a call), so repeated `add` accumulations trap; elsewhere `-into` is an allocation-rate optimization.

- **Aliasing**: element-wise kernels tolerate `out` aliasing an operand (`(vec:add-into acc acc d)`
  is the intended in-place accumulation). `matvec-into` does NOT, and its `eq` guard is in the defun
  AND repeated in every accelerated kernel, because accelerated call sites REPLACE the defun
  (interpreter/JVM compare the BACKING array, wasm-GC `ref.eq`-traps against `x` and `W`, `--no-gc`
  traps on pointer equality). `out`'s LENGTH is not checked.

## Comparison-select ufuncs

`maximum`/`minimum`/`relu`/`clip` (+ `-into`), in both packages. The oracle is the STRICT
COMPARISON SELECT -- `(if (> x y) x y)` etc. -- NEVER an IEEE min/max primitive (`Math.max` and
`f64x2.min/max` propagate NaN and misorder -0.0). The SECOND operand or the bound wins any false
comparison: `(vec:maximum #d(-0.0) #d(0.0))` is `#d(0.0)`, relu maps NaN/-0.0 to 0.0, clip sends NaN
to lo and inverted bounds to hi. Unlike the transcendentals this is CROSS-BACKEND-identical, so
ci-spec carries the -0.0 tie.

An f32 lane compare equals the defun's widened compare, so array-array selects lane-ize at both
widths. Scalar-vs-array keeps the widen rule: an f32 element compares against the FULL double
scalar/bound.

## Element-wise unary ufuncs

Seventeen ufuncs (+ `-into`) in both packages under their numpy names. Every transcendental calls
the fdlibm runtime on WASM, the algorithm the interpreter and JVM run as `StrictMath`, so the digits
are one set on every backend (`.kb/transcendentals.md`).

- **The oracle is each backend's OWN scalar defun** (the emap rule: read widened to f64, apply the
  backend's scalar op, narrow on store), so cross-backend `-0.0`/NaN output stays OUT of ci-spec.
  fdlibm's `exp` answers `0.0` below the smallest denormal, which is what makes a `-infinity` mask
  reach `linalg:softmax` as `0.0` (`.kb/linalg.md`).
- **Lane forms only where they equal the defun**: sqrt, abs, negative, reciprocal
  (`VectorOperators.EXP` is not bit-identical to `StrictMath.exp`; gate
  `JvmSimdVectorTemplate.hasLaneForm`). The rest walk element loops over
  `WasmVecSimdRuntimeBuilder.emitScalarUnaryF64`, which both `--no-gc` lowerings reuse. All f32
  lane forms are exact by the `53 >= 2*24+2` bound.
- **An element function that can answer a complex does not belong in a packed kernel.** CL `sqrt`
  roots negatives into the complex plane and a complex has no packed store, so the scalar path
  signalled where the lane answered NaN. `sqrt`/`log`/`asin`/`acos` use float-domain element
  functions (`vec::%fsqrt`, `%flog`, ..., `%la-` twins): NaN outside the real domain on both paths
  (`.kb/jvm-complex.md`, "Real arguments that leave the real domain"). Scalar `(sqrt x)` stays
  complex-extended. Pinned by `ci-spec.yaml`'s `vec-sqrt-negative-cross-backend`.
- New v128 opcodes go in `am.ik.wasm.Instruction` AND `WasmSections.skipSimd` (which throws on
  unknown 0xFD).

## The four acceleration layers

Layers 0-3 are `--simd` and TOTAL -- one lane kernel per member. Layer 4 is PARTIAL, over the GEMV
pair only.

**A width mismatch DECLINES at every layer, and signals at none**: `%map2` reads every operand
through `aref`, which widens, so the scalar defun computes a mixed `#f`/`#d` pair happily --
`--simd` may not turn that answer into an error. Pinned together by `ci-spec.yaml`'s
`vec-mixed-width-declines-cross-backend` over the `--simd` axis:

- **Layer 0** (`VecSimd`'s `defineFn`): every mismatch arm answers `null`, so the captured scalar
  binding runs.
- **Layer 1** decides BEFORE the call: `JvmSimdCompiler.emitLaneWidthGuard` asks the array operands
  as one (the first picks the width) and a failure takes the `_simdReady()` degrade branch into the
  spliced defun. `JvmSimdVectorTemplate.mixedWidth()` is unreachable from a compiled call site.
- **Layer 3** decides INSIDE the helper, because its call site is a bare `call` with no fallback
  arm: `requireSameKind`'s mismatch arm forwards to the scalar defun (`declineToScalar`: a helper's
  params ARE the defun's, one null env short). `WasmVecSimdCompiler.scalarFallbacks` supplies the
  index; `-1` (no defun) keeps the trap, which no call site can reach.

Layer 2 (`--no-gc`) types the widths apart, so a mixed call is the COMPILE error `incompatible types
F32VEC and F64VEC`.

**Layer 0, interpreter `--simd`** (jdk.incubator.vector, `eval.VecSimdKernels`). The DEFAULT
interpreter is the cross-backend oracle and is unchanged. `eval.VecSimd.available()`/`install` are
the ONLY callers of the kernels. An absent module gives a one-line note + the scalar reference --
UNLESS `--parallel` is also given: then it is a hard error, because the scalar fallback split across
threads is ~100x slower than the kernel, reads as a hang, and the warning scrolls away; `--parallel`
is asked for only by someone about to run something large. The compiled `.class` keeps degrading
either way -- it may run LATER on a module-equipped machine.

**Layer 1, JVM `--simd`**: `JvmSimdCompiler` rewrites call sites to a `<Program>$SimdBridge`
(`JvmSimdRuntimeBuilder` renames `JvmSimdVectorTemplate.class`). Because `mean`/`norm` call
`sum`/`dot`, ANY `--simd` program using the package ships the bridge; the default build is
byte-identical. Running needs `java --add-modules jdk.incubator.vector`; without it `_simdInit`
catches the `LinkageError`, warns once, and every call site falls back to the defun via
`_simdReady()` -- the degrade `--blas`/`--gpu` also give.

**bfloat16 rides layers 0 and 1 only, over TWO pairings**: `sum`/`dot`/`matvec`/`matvec-into` fuse a
bf16 DECODE when the weight-position operand is `#bf16` and every other array operand is `#f`; the
element-wise members with a single-float lane loop fuse over bf16 x bf16 -> bf16. Every other
member and pairing DECLINES to the scalar defun, including a mixed bf16/f32 element-wise call. The
fused reduction is the f32 kernel's over the widened operand BIT FOR BIT, so the width joins the
reduction contract below; `.kb/bfloat16.md`.

**A Q8_0 quantized matrix rides layers 0 and 1 in `matvec`/`matvec-into` only**: an integer dot,
the defun BIT FOR BIT in any fold, so no entry in the reduction contract.
`.kb/quantized-matrix.md`.

**Layer 2, `--no-gc`**: `NoGcWasmCompiler` lowers the whole surface itself (`isSimdCall`). `--simd`
picks v128 (`f64x2` with an odd-tail guard, `f32x4` with a scalar remainder loop); the DEFAULT emits
plain loops with NO `0xFD`, a v128-free MVP module. Layout and type inference are unchanged by the
flag. f32 kernels compute ENTIRELY in f32, promoting only at the value boundary. Traps: v128 locals
need raw value-type bytes (`allocV128Local` 0x7B, `allocF32Local` 0x7D); `f32x4.extract_lane` is
0x1F, NOT 0x1B; sub-opcodes above 127 need the u32-LEB writer; correctness alone does not prove
v128 ran, so unit tests assert `0xFD` presence/absence.

**Layer 3, wasm-GC `--simd`**: kernels (`WasmVecSimdCompiler`, gated on `ctx.simd`) run on emitted
v128 runtime helpers. "`v128.load`/`store` address LINEAR memory" is not a blocker: GC `valtype`
includes `v128`, so `(array (mut v128))` is a legal GC array.

- `data` holds `TYPE_VBLOCK = struct {i32 count, i32 kind, groups}` over `(array (mut v128))`;
  `kind` 0 = f64 (2 lanes), 1 = f32 (4 lanes). `groups` length is `ceil(count / lanes) + 1`; the
  `+1` is a ZERO SENTINEL GROUP so `matvec`'s shuffle window can always read `g+1`. **No kernel has a
  scalar tail**: padding lanes are zero and fold harmlessly to the VALUE -- not to the BITS (see the
  reduction contract).
- **The one place zero padding is not free is a WRITE**: a whole-group store reaches up to
  `lanes - 1` past `count`, where a LONGER `-into` destination has real elements.
  `WasmVecLoops.gcSaveLastGroup`/`gcRestoreLastGroupTail` restore them from the pre-loop value
  (read BEFORE the loop, so an aliased `out` sees its own pre-op lanes).
- The v128 types are appended `--simd` ONLY: declaring `(array (mut v128))` at all requires the SIMD
  proposal, which is what keeps the `simd=n` dead-flag guard working. A default module's type
  section stays a strict PREFIX of a `--simd` one and `FUNC_USER_BASE` is dynamic
  (`WasmLispCompiler.userFuncBase()`), so a non-`--simd` module is BYTE-IDENTICAL.
- **The kernels are standalone runtime functions**, not inline code: a compiled defun's extra locals
  are all `(ref null eq)` and cannot hold a v128. `WasmVecSimdRuntimeBuilder` fixes the local ORDER
  `withLocals(i32, f64, f32, v128, eq, v128arr)` per kernel; all index arithmetic assumes it.
  `_v_set` returns the value AS STORED (an f32 round-trip at single width).
- **`matvec`'s shuffle window**: a row starting mid-group reads `i8x16.shuffle(g[k], g[k+1])`; the
  immediate cannot be computed, so f64 emits 2 row-loop variants and f32 emits 4, chosen per row.
  Safe because the sentinel bounds `k+1` and overhanging lanes multiply `x`'s zero padding.
- **Every writer of a packed array must branch on `ctx.simd`, and the way one gets missed is a test
  matrix counting BACKENDS rather than backends x `--simd`**: the bulk float-bits pair was pinned
  scalar-only, its `ref.cast` to `$f32arr` TRAPS on a vblock, and it shipped green. The E2E half is
  `CiSpecE2eTest`'s `Accel` axis (below).
- **Cost**: ~1.93x on the kernel loop against a linear arena, from `array.get`'s BOUNDS CHECK, which
  no engine hoists. `_v_set` is ~1.85x an `array.set` -- invisible behind a boxed loop, not behind a
  bare element loop (why the linalg members are intercepted; still real for `emap`/`inv`,
  `.kb/linalg-simd.md`). Composes with `--optimize` (the shaker decodes 0xFD immediates) and
  `--component`.

**Layer 4, `--blas` / `--gpu` over the GEMV pair** (`.kb/gpu.md`, `.kb/linalg-blas.md`). Both are
PARTIAL and neither implies `--simd`, so these call sites are a guarded CHAIN -- device -> library
-> lane kernel -> spliced defun, each rung answering `null` for what it declines
(`JvmSimdCompiler.compileMatvecChain`; on the interpreter, install order). A library gemv reorders
the `#f` fold (up to 5.5e-3 relative on llama2's classifier head -- enough to move an `argmax`), so
the examples pinning derived integers (`simd-gemv`, `tiny-llm`, `llm`) are RUN under the flag.

## The f32-reduction precision contract

Every `--simd` backend accumulates an f32 reduction in f32 and promotes ONCE at the value boundary.
At lengths that are multiples of the lane count all four agree; at any other length the two folds
below may differ in the last bit (a contract exception -- unifying them is not worth the blast
radius). The scalar `vec.lisp` reference stays the f64-accumulating oracle. `#d` is untouched.

- **The lane-count pin.** An f32 reduction's value depends on the lane count (`2^24 + 768` at 4
  lanes, `+ 896` at 8, `+ 960` at 16), so `FSPECIES_REDUCE` is `FloatVector.SPECIES_128`, not
  `SPECIES_PREFERRED`, in BOTH `eval.VecSimdKernels` and `JvmSimdVectorTemplate` -- a compiled class
  must not answer differently on an AVX-512 host, and WASM is always `f32x4`. The fold order IS the
  value. Element-wise f32 kernels and f64 reductions keep `SPECIES_PREFERRED`. A `#bf16` operand
  decodes into the same four lanes (`ShortVector.SPECIES_64` -> `IntVector.SPECIES_128`), so every
  probe transfers to that width. The two kernel files mirror each other operation for operation
  (`THRESHOLD = 128`, two-rounding mul-then-add, f64-then-narrow `scaleF`); the eval copy is NOT
  reused from `codegen.jvm` (`eval` may not depend on it).
- **The GEMV row has FOUR accumulators above a column gate** (the multi-accumulator gate), in all
  four implementations, and no fused multiply-add (wasm's `relaxed_madd` may differ between
  engines). `MATVEC_ACC_THRESHOLD = 2 * MATVEC_ACCUMULATORS * lanes = 32` COLUMNS, above
  `MATVEC_ROW_THRESHOLD = 16`. **It must be a pure function of the COLUMN count** -- the row count
  predicts better but is not something the four implementations agree on call for call. 32 is a
  PERFORMANCE number (aarch64); the lane-count pin is a CORRECTNESS one. The f64 `matvecRows` has
  one chain and is unmeasured. Re-measured on stories15M: gate off / 32 / 96 = 343.9 / 393.1 /
  402.6 tok/s, so moving it to 96 is noise.
- **The region BETWEEN the two gates (16-31 columns) is pinned on all four and in the corpus**,
  because nothing else reaches it: the 2^24 probe at 24 columns (`16777234` everywhere) and
  `simd-gemv-below-the-accumulator-gate-cross-backend`. 31 columns agrees too, but by arithmetic
  luck at a partial group.

**A length that is NOT a multiple of the lane count folds DIFFERENTLY BY DESIGN.** Interpreter, JVM
and `--no-gc` run the lane loop and add the leftover as a scalar tail in index order; wasm-GC (and
the component) has no scalar tail and folds `ceil(n/4)` groups with zeroed padding. A 1x31 `#f` GEMV
with `2^24` at column 29 answers 16777244 vs 16777248; `vec:dot` over 131 elements with `4096.0` at
index 127 answers 16777344 vs 16777348. Not unified: a wasm-GC scalar tail breaks what the padded
layout is built for (the sentinel group, the shuffle window, `gcSaveLastGroup`), and padding the
other three costs the hot path -- to move a last bit greedy argmax absorbs.
`partialFinalGroupFoldsTheScalarTailByDesign` (interpreter, JVM) and
`wasmGcSimdPartialFinalGroupFoldsTheZeroPaddedGroupByDesign` (wasm-GC, component, `--no-gc`, scalar
oracle) pin both answers.

The pinning probe: `v = #f(4096.0 1.0 ... 1.0)`, 1024 elements. `4096^2 = 2^24`, where the f32
spacing is 2, so the lane holding it swallows every `1.0` while the other lanes fold theirs.

| probe | scalar (all backends) | `--simd` (all backends, multiple-of-4 lengths) |
|---|---|---|
| `(round (vec:dot v v))`, `v[0] = 4096.0` | 16778239 | **16777984** |
| `(round (vec:sum v))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (aref (vec:matvec m v) 0))`, 1x1024 | 16778240 | **16778176** |
| any of the above at `#d` width | 16778239 | 16778239 |

The GEMV row groups as sixteen lanes (`2^24 + 960`); `vec:dot`/`vec:sum` keep one four-lane chain
(`2^24 + 768`). **A GEMV row and a `vec:dot` over the same vectors are NOT the same bits. Nothing may
assume they agree.** The `singleFloatReductionsAccumulateInSinglePrecision*` and
`partialFinalGroup*` tests are the only inputs crossing `2^24`.

**This is also the one thing a ci-spec case may not do**: the corpus runs scalar and `--simd`
against ONE set of `expected:` lines, so a `#f` reduction crossing `2^24` would read as a defect.
Such a probe belongs in `VecSimdTest`.

## Native image, Web Image, registration

- The `native` profile passes `--add-modules jdk.incubator.vector` + `-H:+VectorAPISupport`; without
  the latter the Vector API emulates per lane, 6-32x SLOWER than scalar. GraalVM 25 refuses it with
  `-H:+SharedArenaSupport` (needed by JLine's FFM provider), so
  `JLineRepl.selectNativeImageTerminalProvider()` pins `org.jline.terminal.provider=jni`; forcing
  `ffm` on the binary crashes.
- `src/web/java/.../Target_VecSimd.java` substitutes `available()` and `install(...)` so the
  incubator module never enters the browser image. It works only because those are the ONLY entry
  points; a new PUBLIC `VecSimd` method touching the kernels breaks it, and only the Pages
  workflow's Web Image build would notice.
- `resource-config.json` registers `vec.lisp` and `JvmSimdVectorTemplate.class`.

## Writing a `--simd` example or benchmark

- **`THRESHOLD = 128` is compared against the ROW LENGTH**, not the total element count; GEMV rows
  have `MATVEC_ROW_THRESHOLD = 16`. Below those the interpreter and JVM run scalar; wasm-GC and
  `--no-gc` have no threshold.
- **Print only INTEGERS** (`argmax` is the trick); WASM prints floats to ~7 digits.
- `get-internal-real-time` is in MILLISECONDS -- an INTEGER on interpreter/JVM, a FLOAT on WASM --
  so an elapsed-time line must never be checked.
- `--no-gc` cannot compile `linalg:` (`&optional` in `linalg::%la-make`) and rejects `vec:matvec` in
  an example, so such examples are `[interpreter, jvm, wasm]` only.
- `ExamplesE2eTest`'s interpreter leg fails spuriously when Graal prints a "Systemic Graal
  compilation failure" warning onto stdout -- re-run.
- **Take every lane-kernel number under BOTH JITs** (Graal, and C2 via `-XX:-UseJVMCICompiler`,
  what a stock OpenJDK runs a `.class` under). **The C2 inlining cliff**: a method overrunning C2's
  inlining budget for a Vector API chain gets every vector BOXED -- same bits, no warning; one shape
  measured 1.51x under Graal and 0.20x under C2. Rule: ONE SMALL KERNEL METHOD PER WIDTH, no decoder
  shared behind a flag, no width switch inside the lane loop. A number without its JIT is not a
  number.
- **The second JIT cliff is a CONVERSION, and it is Graal's**: `convertShape(I2D, SPECIES_128,
  part)` is not intrinsified by Oracle GraalVM 25.0.4 (a GEMV ran 1000x slower, C2 fine). Convert
  int lanes with `convert(VectorOperators.I2F, 0)` (exact below 2^24) and accumulate in f32; a
  scalar oracle still mirrors it because `53 >= 2*24+2`. Time every lane conversion under both JITs.
- **The third JIT cliff is a PART, and it is C2's**: `convertShape(..., part != 0)` is `slice` +
  part-0 conversion, an iota shuffle and two `rearrange`s that C2 compiles as written (Graal folds
  it). Widen from the NARROWER species as part 0; never a part-1 conversion in a lane loop. Also C2:
  `NodeCountInliningCutoff` (18000 nodes) is a kernel's size budget and the Vector API spends
  hundreds per call (two rows a pass ran BOXED at 0.1x); a helper compiled standalone is refused
  inlining into another caller past `InlineSmallCode` (1000 bytes on aarch64) -- keep helpers
  private to one caller, and time one shape per JVM.
- **Benchmarking discipline.** Run SEQUENTIALLY; N >= 9 samples, print them ALL (GraalVM scalar
  timings can be bimodal). Allocation: `-XX:+UseEpsilonGC -Xmx12g -Xlog:gc`, heap-used-at-exit. zsh
  does NOT word-split an unquoted `$FLAGS`. JVM-specific measurements belong here, never in
  `doc/**` or an example header. Vary the axis you are not thinking about (every "GraalVM cannot
  vectorize" experiment used `#f`; the first `#d` one was 1100x faster).

## The E2E `--simd` axis

`CiSpecE2eTest` crosses the four backends with two `Accel` legs -- default and `--simd` -- so every
corpus and `standalone:` case runs eight ways, both checking the SAME `expected:` lines.

**Why the whole corpus rather than the packed-float cases**: `--simd` is a different data
REPRESENTATION (a `TYPE_VBLOCK` instead of `$f32arr`/`$f64arr`), and which primitives touch it is not
enumerable by eye -- the `widen-float-bits` trap shipped green with ci-spec cases and unit tests on
all four backends. A per-case `simd:` flag is worse than doubling: the corpus is ONE program per
backend, so a flag means a process start per case per pass, and the per-case cost dominates a pass
(measured).

**`--simd` FAILS OPEN**: with `jdk.incubator.vector` off the module graph the CLI and `_simdInit`
warn and run scalar with byte-identical stdout. `CiSpecE2eTest.assertSimdTookEffect` asserts the
degrade warning is ABSENT (interpreter, JVM) and the emitted artifact DIFFERS from the scalar leg's
(JVM class, both wasm modules). Verified by breaking it three ways: forcing `WasmFloat16Compiler`'s
`ctx.simd` false (RED on both wasm `--simd` legs), dropping the JVM launcher's `--add-modules` (RED on
`JVM --simd` with every output comparison green -- the fail-open), and a binary without
`--add-modules` (RED on `INTERPRETER --simd`).

**Length gates**: the interpreter/JVM kernels gate on length (`THRESHOLD`, `MATVEC_ROW_THRESHOLD`,
`MATVEC_ACC_THRESHOLD`), and short corpus cases take the scalar fallback. The corpus's shapes above
every gate are `transformer-greedy-decode-text-cross-backend` and
`gated-delta-rule-greedy-decode-text-cross-backend`, decoded to TEXT so a moved argmax fails loudly
(`.kb/test-execution.md`, "Decoded text and the argmax alarm").

**`--no-gc` and `--parallel` are NOT axes here.** `--no-gc` refuses most of the corpus; its axis is
what it refuses (`NoGcWasmCompilerTest`, `WasmLispCompilerIntegrationTest`'s
`noGcRuns*UnderBothLowerings`). `--parallel` changes reduction ORDER, not representation, inside the
kernels `--simd` selected, so its blast radius is enumerable and pinned there
(`JvmSimdParallelCompilerTest`: serial == parallel == widened; the `parallel: true` example); it is
refused on both wasm backends. Revisit if a `--parallel` defect lands outside the kernels.

## Tests

- `eval/VecSimdTest` (every kernel vs the oracle at both widths, below/above `THRESHOLD`; bf16
  fused-equals-widened, the bf16 lane-count probe, declined pairings; the
  `LispFunction`-vs-`LispLambda` interception guard -- the printed text no longer tells them apart;
  `-into` aliasing; mixed-width and rank errors), `eval/LinalgSimdTest`, `FloatWidthTest`.
- `JvmSimdAccelCompilerTest`, `JvmLinalgSimdAccelCompilerTest`, `JvmSimdModuleFallbackTest`,
  `JvmBFloat16ArrayTest` (fused decode vs widened-f32 kernel and the interpreter's `--simd`),
  `JvmSimdParallelCompilerTest`, `eval/VecSimdBf16KernelsTest`,
  `codegen/jvm/JvmSimdVectorTemplateBf16Test`.
- `NoGcWasmCompilerTest`: `0xFD` presence/absence, load/store opcodes, `#f` narrow/widen, compile
  errors, `{expAndSign,logAndTanh,sinCosTan,arcAndHyperbolic}LowerNativelyOnNoGc`,
  `intoKernelsCallTheBumpAllocatorOnlyForTheConstructors` (matched on `i32.shl; i32.add; call
  $__ronto_alloc` -- a bare `0x10 <idx>` scan false-positives inside v128 immediates).
- `WasmLispCompilerTest`: v128 local declarations (the one part of a code section that decodes
  without an opcode walker), `simdAppendsExactlyTheVecTypeBlockAndTheVecFunctionBlock`.
- `WasmLispCompilerIntegrationTest` (Docker+wasmtime): the `wasmGcSimd*` family
  (`IsByteIdenticalToTheScalarPathOverTheWholeVecSurface` and siblings,
  `PackedArraysAreCollectedRatherThanAccumulated`), the dead-flag guard
  `wasmGcSimdModuleNeedsTheSimdProposalAndTheDefaultOneDoesNot` (`wasmtime --wasm simd=n --wasm
  relaxed-simd=n`; relaxed-simd must be disabled too or wasmtime rejects the combination), and
  `noGcRuns*UnderBothLowerings` (compares `--no-gc` against a wasm-GC run, not a constant).
- ci-spec: the whole corpus x {default, `--simd`}, plus `vec-kernels-cross-backend`,
  `vec-destination-passing-kernels`, `simd-gemv-below-the-accumulator-gate-cross-backend`,
  `comparison-select-ufuncs-cross-backend-cases`, `log-tanh-exact-cross-backend-cases`,
  `sin-cos-tan-exact-cross-backend-cases`. Run the native `CiSpecE2eTest` after editing any.
  `examples/ml/nn-vec.lisp` runs via `ExamplesE2eTest`. Manual `--no-gc`: `wasmtime run --invoke
  <fn> module.wasm <args>` (result on stderr).

## Not done / follow-ups

- `linalg:` acceleration: a linalg kernel is PARTIAL (declines general arrays, mixed widths, plain
  numbers, shape errors by returning null). `.kb/linalg-simd.md`; widths in `.kb/linalg.md`.
- GEMM lives in `linalg:` and needs no transpose: `ikj` makes `b`'s rows contiguous AND preserves
  the oracle's summation order, so `#d` is bit-identical.
- `--no-gc` out of scope: rank-2 `#d`/`#f` literals, rank >= 3, `array-dimensions` on the matrix,
  general rank-2 arrays.
- A stories15M-scale llama2 demo with real weights (a tokenizer and a weight loader are left).
