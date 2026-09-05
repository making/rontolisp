# vec: package + packed float-array acceleration

`vec:` is a set of portable packed-float vector kernels defined as plain defuns in
`src/main/resources/am/ik/rontolisp/eval/vec.lisp` -- the cross-backend ORACLE -- and intercepted by
four acceleration layers, each of which must match its own backend's defun bit for bit. The
representation lives in `LispFloatArray`, `JvmFloatArrayRuntimeBuilder`, `WasmArrayCompiler`
(`$farray`) and `NoGcWasmCompiler` (`F64VEC`).

## The type it rides on

A vector is a rank-1 packed `(array double-float)` -- what `#d(...)` and
`(make-array n :element-type 'double-float)` produce, so generic `aref`/`(setf aref)`/`length`/
`make-array` interoperate. Storing a non-real is a type error; no boxed fallback. `#f(...)` /
`:element-type 'single-float` is a DIFFERENT width, and `vec:` is WIDTH-POLYMORPHIC over both:
element-wise kernels preserve the input width (`vec::%make-like`), reductions fold to an f64 scalar.

- Interpreter: `record LispDoubleFloatArray(double[] data, int[] dims)`, one width of the sealed
  `LispFloatArray` (`LispSingleFloatArray(float[])`, `LispBFloat16Array(short[])`,
  `.kb/bfloat16.md`). No in-array header.
- JVM: a bare primitive array with an embedded header whose LAYOUT IS WIDTH-DEPENDENT, owned by
  `codegen/jvm/JvmPackedFloatWidth`: `double[]`/`float[]` are `[rank, dim..., data...]` (data offset
  `1 + rank`), bfloat16 `short[]` is `[rank, hi_0, lo_0, ..., data...]` (offset `1 + 2 * rank`; a
  `short` cannot hold a dimension above 32767). **No emitter spells the offset itself** -- a site
  hard-coding `1 + rank` reads a length-1 bfloat16 array's rank word as its element, silently.
- wasm-GC: `TYPE_FARRAY` struct whose data field holds `TYPE_F64ARR = (array (mut f64))` or
  `TYPE_F32ARR = (array (mut f32))` (`ref.test $f32arr`) -- except under `--simd`, where it holds a
  `TYPE_VBLOCK` (layer 3); the struct type is unchanged, the field `(ref null eq)` either way.
- `--no-gc`: `[count:i32][count f64]` (`Ty.F64VEC`, 8-byte stride) or `[count:i32][count f32]`
  (`Ty.F32VEC`, 4-byte stride) in linear memory. Rank-2 is a distinct pointer kind
  `Ty.F64MAT`/`F32MAT` -> `[rows:i32][cols:i32][rows*cols row-major]`.
- It is also a JAVA boundary type: `rontolisp:jvm-export`'s `:float-vector`/`:float-matrix` hand one
  over as `am.ik.rontolisp.runtime.RontoFloatArray`, a handle that ALIASES it (a copying boundary
  measured ~10x the kernel), [jvm-export.md](jvm-export.md).

## Asking a packed array its width

`LispFloatArray` is sealed, so EVERY width test must be an exhaustive `switch` over it with NO
`default` arm -- never `instanceof LispSingleFloatArray` read negatively as "therefore double",
never a cast straight to one record. Sites: `eval/Environment`, `eval/LinalgBlas`, `eval/VecSimd`,
`eval/LinalgSimd`, `eval/LinalgGpu` and nowhere else in `src/main` (86 as of 2026-09-03). MEASURE the
count (add a throwaway third permit, `./mvnw compile`), never count by hand.

- **The rule is not "no `default`" -- an arm matching more than one permit IS a default however
  spelled.** `NoGcWasmCompiler.typeOf`'s `case LispFloatArray ignored -> Ty.F64VEC` emitted bfloat16
  literals at eight bytes an element, no diagnostic. A supertype pattern is correct exactly when the
  arm's answer is width-INDEPENDENT (`arrayp`, rank, total size, a no-op walk arm).
- **Exhaustiveness is checked in the STATEMENT form for the sealed type, and only in the EXPRESSION
  form for the width enum** (JLS 14.11.2: an enum selector makes a switch legacy). Write a
  `FloatWidth` switch as an EXPRESSION; do not churn sealed-type sites for it.
- `FloatWidth` and the permits are in bijection (`FloatWidthTest`) -- it catches a CONSTANT added
  ahead of its permit, which leaves every enum switch looking exhaustive. Switch over
  `LispFloatArray.width()` only for the width as a VALUE (the `%la-gather-strided` wire).
- Deliberately still `instanceof`: a same-width guard inside an arm; the must-be-double requirements
  (`%la-adam-step`, `%la-rng-fill`, `%la-dropout-mask`); `floats`/`doubles` in
  `LinalgBlas`/`LinalgSimd`/`LinalgGpu`; `eval/PackedBuffer.of`, `eval/GeomKernels`.
- **The one width test that cannot be a compile error** is `make-array`'s in `Environment`: it
  dispatches on the `:element-type` STRING. A new width must be added there by hand.

## vec.lisp -- the scalar reference

`VecLibrary` splices/loads it like `LinalgLibrary`: the interpreter lazy-loads on the first
resolution of a `vec:`-qualified function; `RontoLispCli` / `RontoPlayground` / corpus+e2e helpers
call `VecLibrary.process(program)` after user-macro expansion; `--no-gc` is gated OFF the splice
(`!(outputFile.endsWith(".wasm") && noGc)`) and intercepts the surface natively.

Members: `zeros`/`ones`/`arange`/`from-list`/`to-list` (first three take `:element-type` through
`vec::%make`); `aref`/`aset`/`length`; `add`/`sub`/`mul`/`div`/`scale` plus the CL operator spellings
`+`/`-`/`*`/`/`, STRICTLY BINARY aliases onto the same kernels (every `vec:` kernel is fixed-arity
and allocation-explicit -- the reason `-into` exists -- and `--no-gc` has no cons list to fold over);
the unary ufuncs `exp`/`log`/`tanh`/`sin`/`cos`/`tan`/`asin`/`acos`/`atan`/`sinh`/`cosh`/`sqrt`/
`abs`/`square`/`negative`/`sign`/`reciprocal`; the selects `maximum`/`minimum`/`relu`/`clip`; the
reductions `sum`/`dot`/`mean`/`norm`; `matvec` (GEMV, rank-2 x rank-1 -> fresh rank-1).

- `from-list`/`to-list` need cons lists -- portable-backends-only, a `--no-gc` compile error.
  `(setf (vec:aref v i) x)` -> `(vec:aset v i x)` via `LispMacroExpander.expandSetf`
  (`VEC_QUALIFIED_AREF`).
- `mean`/`norm` are never intercepted directly; they accelerate transitively through `sum`/`dot`.
  So do `square`/`reciprocal` where a defun exists (`vec:square` = `(vec:mul v v)`,
  `linalg:reciprocal` = `(linalg:div 1 a)`); `vec:reciprocal` has its own kernel. `#'vec:dot` still
  names the scalar defun on every backend.
- `matvec` is the ONE `vec:` member `--gpu` intercepts (allocating form only): `LinalgGpu.installVec`
  / `JvmSimdCompiler.compileGpuMatvec`, above 2^17 matrix elements and only over a resident,
  unwritten matrix, accumulating in DOUBLE like the defun. `.kb/gpu.md`.
- `matvec`/`matvec-into` are the two members `--parallel` splits across threads, bit-identically
  (`.kb/simd-parallel.md`); no reduction is.

## Destination-passing `-into` kernels

Every vector-returning kernel has an `-into` sibling (`add-into`..`scale-into`, `matvec-into`,
`exp-into`..`reciprocal-into`, `maximum-into`..`clip-into`) writing into a caller-supplied
destination (argument 1, CL's `map-into` order) and RETURNING it. Reductions have none. Emitters:
`eval/VecSimdKernels.addInto`..`matvecIntoF` + `VecSimd.installInto`;
`JvmSimdVectorTemplate.simdAddInto`..`simdMatvecInto` + `JvmSimdCompiler.ARITIES`;
`WasmVecSimdRuntimeBuilder._vec_add_into`..; a `boolean into` threaded through
`NoGcWasmCompiler.compileSimd/ScalarElementwise{,F32}` and `compileSimd/ScalarScale{,F32}`.

Only `--no-gc` leaks: it bump-allocates with NO FREE, reclaiming by popping the arena at an export
boundary (auto-reset on a scalar return, `__ronto_alloc_mark`/`_reset` for a host), never within a
call, so `-into` makes the bump high-water equal the live set (12000 accumulations over 65536
elements: `add-into` peaks at 13.7 MB, `add` at 4.31 GB then traps). Elsewhere it is purely an
allocation-rate optimization. `linalg` arrays behave identically.

- **Aliasing**: element-wise kernels tolerate `out` aliasing `a` and/or `b`, so
  `(vec:add-into acc acc d)` is the intended in-place accumulation. `matvec-into` does NOT, and its
  `eq` guard is in the defun AND repeated in every accelerated kernel, because accelerated call
  sites REPLACE the defun: interpreter/JVM compare the BACKING array (`r.data() == vx.data()`),
  wasm-GC `ref.eq`-traps against BOTH `x` and `W`, `--no-gc` traps on pointer equality
  (`unreachable`). Widths must match across `out` and the operands; `out`'s LENGTH is not checked.

## Comparison-select ufuncs

`maximum`/`minimum` (binary), `relu` (unary), `clip` (unary + two scalar bounds), each with `-into`,
in BOTH packages (linalg has `maximum`/`minimum` kernels only; `clip` = `(minimum (maximum a lo) hi)`
and `relu` = `(maximum a 0.0)` compose them).

The oracle is the STRICT COMPARISON SELECT -- `(if (> x y) x y)`, `(if (< x y) x y)`, relu
`(if (> x 0.0) x 0.0)`, clip the min-max nesting -- NEVER an IEEE min/max primitive (`Math.max` and
`f64x2.min/max` propagate NaN and misorder -0.0). The SECOND operand or the bound wins any false
comparison: `(vec:maximum #d(-0.0) #d(0.0))` is `#d(0.0)`, `maximum(x, NaN)` keeps the NaN, relu maps
NaN/-0.0 to 0.0, clip sends NaN to lo and inverted bounds (lo > hi) to hi. Unlike the transcendentals
this is CROSS-BACKEND-identical, so ci-spec carries the -0.0 tie.

An f32 lane compare equals the defun's widened compare, so array-array selects lane-ize at both
widths (`WasmVecLoops.gcMap2Select`/`simdMap2Select`/`scalarMap2Select`, `U_RELU` for relu).
Scalar-vs-array keeps the widen rule -- `gcBroadcastSelectF64` (WITH the save/restore bracket) for
an f64 scalar, `_v_get`/`_v_set` widened against the FULL double scalar for an f32 one, and
`buildClip`/`compileSimdClip` comparing widened elements against full-double bounds. vec FUNC_COUNT
47 -> 55 (CLIP_INTO is the first `TYPE_CALLABLE_BASE + 3` four-param kernel), linalg 30 -> 32,
`userFuncBase()` 77 -> 87.

## Element-wise unary ufuncs

The seventeen ufuncs (+ `-into`) exist in BOTH packages under their numpy names. They emptied
`BuiltinFunctionWrappers.WASM_UNSUPPORTED` -- every transcendental built-in now compiles on WASM.
WASM software scalars: `WasmAtanCompiler` (~1e-15, `-0.0` PRESERVED since there is no `i32.trunc`;
acos = `2*atan(sqrt((1-x)/(1+x)))` NOT `pi/2 - asin`, so `(acos 1)` is exactly 0.0),
`WasmSinhCoshCompiler` (NaN/+-inf branches must PRECEDE the exponential, whose Horner maps -inf to
+inf), `WasmSinCosCompiler` (Cody-Waite, ~1e-11 for |x| <= ~1e6), `WasmLogCompiler` (~1e-10),
`WasmTanhCompiler` (argument clamped to +-40, so large inputs saturate to exactly +-1.0).
`(sin -0.0)`/`(tan -0.0)`/`(tanh -0.0)` are `0.0`; wasm `signum` maps `-0.0`/NaN to `0.0`.

- **The oracle is each backend's OWN scalar defun** (the emap rule: read widened to f64, apply the
  backend's scalar op, narrow on store), so cross-backend `-0.0`/NaN/low-digit output stays OUT of
  ci-spec. The one edge where wasm's `exp` is EXACTLY the JVM's is underflow:
  `WasmExpCompiler.UNDERFLOW_CLAMP` clamps the reduced Horner polynomial at `f64.max(p(t), 0.0)`
  before the squarings, because `p` goes negative below its root (`t = -2.18`, `x = -558`) and the
  even squaring count turned that into a huge POSITIVE value (`(exp -1000)` was `2.4e125`). It is
  what makes a `-infinity` mask reach `linalg:softmax` as `0.0` (`.kb/linalg.md`); `emitExpF64`
  carries the same instruction so `--simd`/`--no-gc` stay bit-identical to the defun.
- **Lane forms only where they equal the defun.** Interpreter/JVM and wasm-GC lane-ize sqrt, abs,
  negative and reciprocal only (`VectorOperators.EXP` is not bit-identical to `Math.exp`; gate
  `JvmSimdVectorTemplate.hasLaneForm`); exp/log/tanh/sin/cos/tan/sign walk element loops over
  `WasmVecSimdRuntimeBuilder.emitExpF64`/`emitLogF64`/`emitTanhF64`/`emitSinCosF64`/`emitSignumF64`,
  which `NoGcWasmCompiler.compileSimdUnaryF64` reuses, so BOTH `--no-gc` lowerings emit the identical
  loop (no `0xFD`). All f32 lane forms are exact by the `53 >= 2*24+2` bound. The scalar
  `(exp x)`/`(log x)`/etc. builtins remain unknown on `--no-gc`.
- New v128 opcodes (`f32x4/f64x2.sqrt/abs/neg/lt/gt`, `v128.bitselect`) go in
  `am.ik.wasm.Instruction` AND `WasmSections.skipSimd` (which throws on unknown 0xFD).

## The four acceleration layers

Layers 0-3 are `--simd` and TOTAL -- one lane kernel per member, so the JVM call site is a bare
`INVOKESTATIC` and the interpreter native never declines. Layer 4 is PARTIAL, over the GEMV pair only.

**Layer 0, interpreter `--simd`** (jdk.incubator.vector): the eight vectorizable kernels run on
`eval.VecSimdKernels`. The DEFAULT interpreter is unchanged -- it is the cross-backend oracle, and
`ci-spec.yaml` never passes `--simd`. `eval.VecSimd.available()`/`install(Environment)` are the ONLY
callers of the kernels; `LispEvaluator.setSimd(true)` installs in `resolveFunction`'s lazy-load hook
(so the REPL is accelerated too) and `RontoLispCli`'s `enableSimd` probes `available()` first
(absent module -> a one-line note + the scalar reference).

**Layer 1, JVM `--simd`**: `JvmSimdCompiler` (from `JvmExprCompiler`, gated
`usesSimd = simdAccel && programUsesAnyAcceleratedSimdOp` -> `Ctx.simdOps`) rewrites the call sites
to an embedded `RontoLispSimdBridge` (`JvmSimdRuntimeBuilder` renames `JvmSimdVectorTemplate.class`
into the program's package and emits `_simdInit`, like the `java:` bridge). Because `mean`/`norm`
always call `sum`/`dot`, ANY `--simd` program using the package embeds the bridge. Running it needs
`java --add-modules jdk.incubator.vector`; the default build is byte-identical. **Module-absence
degrade**: `_simdInit` CATCHES the `LinkageError`, warns once on stderr, leaves `_simdAvailable`
false, and every call site checks `_simdReady()` before falling back to the defun -- the same
degrade `--blas`/`--gpu` give with no library/device.

**bfloat16 rides layers 0 and 1 only, over ONE pairing**: `sum` / `dot` / `matvec` / `matvec-into`
fuse a bf16 DECODE into the lane loop when the operand at the weight position is `#bf16` and every
other array operand is `#f` -- bf16 weights against f32 activations, the only pairing the plan has.
Every other member and every other pairing DECLINES to the scalar defun, INCLUDING a mixed
bf16/f32 element-wise call, which `--simd` used to raise the fixed-width error on and which the
defun computes happily; a flag may not turn an answer into an error. The fused answer is the f32
kernel's over the widened operand BIT FOR BIT, so the width joins the reduction contract below
rather than adding one of its own. Mechanics, the decline sites and the cache-resident cost:
`.kb/bfloat16.md`.

**Layer 2, `--no-gc`**: `NoGcWasmCompiler` lowers the whole surface itself; `isSimdCall(name)` (a
`"vec:"` prefix test) dispatches in `collectCalls`, `typeOf`/`typeOfSimd` and
`compileCall`/`compileSimd`. `--simd` picks real fixed-width v128 (`f64x2` with a one-element
`emitOddTailGuard`, `f32x4` with an `openScalarTailLoop` remainder); WITHOUT it -- the DEFAULT --
`compileScalar{Elementwise,Scale,Sum,Dot}` emit plain linear-memory loops with NO `0xFD`, a v128-free
MVP module. The `[count][data]` layout is byte-identical either way and type inference is UNCHANGED
by the flag, so both lowerings compute the same result. f32 kernels compute ENTIRELY in f32
(llama2.c / `FloatVector` semantics), promoting only at the value boundary, so tests use integer /
power-of-two inputs. Traps: v128 locals need raw value-type bytes in `Fn.extraLocalTypes`
(`allocV128Local` 0x7B, `allocF32Local` 0x7D, `withLocalsRaw`); `f32x4.extract_lane` is 0x1F, NOT
0x1B (`i32x4.extract_lane`); sub-opcodes above 127 (`f64x2.add` 0xF0, `f32x4.add` 0xE4) need the
u32-LEB writer; and correctness alone no longer proves v128 ran, so unit tests assert `0xFD`
presence/absence directly.

**Layer 3, wasm-GC `--simd`**: fifty-four kernels (`WasmVecSimdCompiler.handles/compile` in
`WasmExprCompiler.compileCons`, gated on `ctx.simd`) run on emitted v128 runtime helpers. The
apparent blocker -- "`v128.load`/`store` address LINEAR memory" -- is FALSE: GC
`fieldtype ::= storagetype ::= valtype | packedtype` and `valtype` includes `vectype = v128`, so
`(array (mut v128))` is a legal GC array and `array.get` yields a v128. No arena, no `memory.grow`.

- `data` holds a `TYPE_VBLOCK = struct {i32 count, i32 kind, (ref null eq) groups}` over
  `TYPE_V128ARR = (array (mut v128))`. `kind` 0 = f64 (2 lanes), 1 = f32 (4 lanes), the runtime width
  tag replacing `ref.test $f32arr`. `groups` length is `ceil(count / lanes) + 1`; the `+1` is a ZERO
  SENTINEL GROUP so `matvec`'s shuffle window can always `array.get g+1` without a bounds trap.
  **No kernel has a scalar tail**: `array.new_default` zero-initializes and nothing writes past
  `count`, so the padding lanes are zero and every kernel folds them harmlessly.
- **The one place the zero padding is not free is a WRITE**: a whole-group store reaches up to
  `lanes - 1` past `count`, which an `-into` destination LONGER than its operands has REAL elements
  at. `WasmVecLoops.gcSaveLastGroup`/`gcRestoreLastGroupTail` bracket the group loop and blend the
  last written group, restoring lanes `>= count % lanes` from the destination's pre-loop value (read
  BEFORE the loop, so an aliased `out` still sees its own pre-op lanes).
- Four types, `--simd` ONLY, appended after `TYPE_F32ARR` (`TYPE_V128ARR`, `TYPE_VBLOCK`,
  `TYPE_V_GET`, `TYPE_V_SET`): declaring an `(array (mut v128))` at all requires the SIMD proposal, so
  the type must NOT appear in a default module -- that is what keeps the `simd=n` dead-flag guard
  working. Wrapper type bases read `WasmLispCompiler.fixedTypeCount()`, and a default module's type
  section stays a strict PREFIX of a `--simd` one, so component blobs are untouched. Likewise
  `FUNC_VEC_BASE = FUNC_WRITE_STR_GC + 1` makes `FUNC_USER_BASE` dynamic
  (`WasmLispCompiler.userFuncBase()` via `Ctx.userFuncBase`, read only by `WasmLambdaCompiler` and
  `WasmRuntimeBuilder.buildDispatchBody`); a non-`--simd` module is BYTE-IDENTICAL.
- **The kernels are standalone runtime functions**, not inline code: a compiled defun body's extra
  locals are all `(ref null eq)`, so it cannot hold a v128 local. `WasmVecSimdRuntimeBuilder`
  hand-writes `withLocals(i32, f64, f32, v128, eq, v128arr)` per kernel -- that fixed ORDER is what
  all the index arithmetic assumes. A mixed-width call traps (`requireSameKind`).
  `_v_new`/`_v_get`/`_v_set` (the first three emitted functions) own the width branch AND the
  immediate-lane branch; `_v_set` returns the value AS STORED (an f32 round-trip at single width).
- **`matvec`'s shuffle window**: a row starting mid-group reads
  `i8x16.shuffle(groups[base+k], groups[base+k+1])`, immediate `[c, c+1, .. c+15]` with
  `c = off * elementBytes`. The immediate cannot be computed, so f64 emits 2 row-loop variants and
  f32 emits 4, chosen by an `if`-chain on `off` once per row. Safe because the sentinel bounds the
  final `base+k+1` and the overhanging lanes multiply `x`'s ZERO PADDING.
- **The rest of the packed surface** branches on `ctx.simd` at compile time:
  `WasmArrayCompiler.compilePackedMakeVblock`/`emitPackedReadF64Vblock`/`emitPackedWriteF64Vblock`/
  `compileElementType`, `WasmQuoteCompiler.compilePackedVblockLiteral`,
  `WasmRuntimeBuilder.emitPrintArray`, `WasmFloat16Compiler` (`Layout.VBLOCK`);
  `compilePackedMakeVblock` skips the fill loop only for an absent or literal POSITIVE-zero
  `:initial-element`. **Every writer of a packed array has to be on that list, and the way one gets
  missed is a test matrix counting BACKENDS rather than backends x `--simd`** -- the bulk float-bits
  pair was pinned scalar-only and its wasm `ref.cast` to `$f32arr`/`$f64arr` TRAPS on a vblock; it
  shipped green. `WasmVecLoops` holds the linear v128 bodies, the scalar ones AND the GC group bodies
  (`gcMap2`/`gcScale`/`gcSum`/`gcDot`); `NoGcWasmCompiler` delegates to the linear ones with its
  locals in the original order.
- **Cost**: the GC representation costs ~1.93x on the kernel loop against a linear arena, the cause
  being `array.get`'s BOUNDS CHECK, which no engine hoists (typing the group locals `(ref $v128arr)`
  buys nothing). `_v_set` is ~1.85x an `array.set` -- invisible behind a BOXED loop, not behind a
  bare element loop: before the linalg interception `linalg:add` went 205 ms -> 230 ms under
  `--simd`, a 12% PESSIMIZATION, fixed by intercepting the fifteen `linalg:` members; still real for
  `emap`/`inv`, `.kb/linalg-simd.md`. Composes with `--optimize` (the shaker's `skipSimd` decodes
  0xFD, incl. `v128.const` / `i8x16.shuffle`'s 16 immediate bytes and `replace_lane`'s lane byte) and
  `--component`.

**Layer 4, `--blas` / `--gpu` over the GEMV pair**: `--gpu` takes `vec:matvec` (`.kb/gpu.md`),
`--blas` takes both as `cblas_?gemv` (`.kb/linalg-blas.md`). Both are PARTIAL and neither implies
`--simd`, so these two call sites are a guarded CHAIN -- device -> library -> lane kernel -> spliced
defun, over one set of temps, each rung answering `null` for what it declines and the bottom rung
total. `JvmSimdCompiler.compileMatvecChain` emits it (a `--simd`-only build keeps layer 1's bare
`INVOKESTATIC` byte for byte); on the interpreter the same order is install order: `VecSimd.install`
-> `LinalgBlas.installVec` -> `LinalgGpu.installVec`. Precision: a library gemv reorders the `#f`
fold, up to 5.5e-3 relative on llama2's classifier-head shape -- enough to move an `argmax`, so the
examples pinning derived integers (`simd-gemv`, `tiny-llm`, `llama2`) are RUN under the flag.

## The f32-reduction precision contract

Every `--simd` backend accumulates an f32 reduction in f32 and promotes ONCE at the value boundary,
so all four agree; the scalar `vec.lisp` reference stays the more accurate f64-accumulating oracle.
`#d` is untouched.

- **The lane-count pin.** An f32 reduction's value depends on the lane count (`2^24 + 768` at 4
  lanes, `+ 896` at 8, `+ 960` at 16), so `FSPECIES_REDUCE` is `FloatVector.SPECIES_128`, not
  `SPECIES_PREFERRED`, in BOTH `eval.VecSimdKernels` and `JvmSimdVectorTemplate` -- a compiled class
  must not answer differently on an AVX-512 host, and the WASM kernels are always `f32x4`.
  Element-wise f32 kernels and f64 reductions keep `SPECIES_PREFERRED`. A `#bf16` operand decodes
  into those same four lanes (`ShortVector.SPECIES_64` -> `IntVector.SPECIES_128`, pinned for the
  same reason) and accumulates in f32, so every probe below transfers verbatim to that width -- 2^24
  and 1.0 are both exact in bfloat16. The two kernel files mirror
  each other operation for operation (`THRESHOLD = 128`, two-rounding mul-then-add, f64-then-narrow
  `scaleF`), so interpreter `--simd` == compiled `.class --simd` bit for bit; the eval copy is NOT
  reused from `codegen.jvm` (`eval` may not depend on it).
- **The GEMV row has FOUR accumulators above a column gate**, in all four implementations at once,
  and no fused multiply-add (wasm's `relaxed_madd` may differ between engines, so it can never carry
  a bit-identity contract). The gate is `MATVEC_ACC_THRESHOLD = 2 * MATVEC_ACCUMULATORS * lanes = 32`
  COLUMNS, under every real head dimension and above `MATVEC_ROW_THRESHOLD = 16`. **It must be a
  pure function of the COLUMN count** -- row counts are deliberately NOT consulted even though they
  predict better, because that would depend on something the four implementations cannot agree on
  call for call. Do not "improve" this with the row count. 32 is a PERFORMANCE number,
  machine-dependent (aarch64); the lane-count pin is a CORRECTNESS one. The f64 `matvecRows` still
  has one chain and is UNMEASURED.

The pinning probe: `v = #f(4096.0 1.0 ... 1.0)`, 1024 elements. `dot(v,v) = 4096^2 + 1023 = 16778239`
exactly; `4096^2` is `2^24`, where the f32 spacing is 2, so the lane holding it swallows every `1.0`
while the other three lanes fold 256 ones each.

| probe | scalar (all backends) | `--simd` (all backends) |
|---|---|---|
| `(round (vec:dot v v))`, `v[0] = 4096.0` | 16778239 | **16777984** |
| `(round (vec:sum v))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (aref (vec:matvec m v) 0))`, 1x1024 | 16778240 | **16778176** |
| any of the above at `#d` width | 16778239 | 16778239 |

The GEMV row groups as sixteen lanes (`2^24 + 960`); `vec:dot`/`vec:sum` keep one four-lane chain
(`2^24 + 768`). **A GEMV row and a `vec:dot` over the same two vectors are the same value
mathematically and NOT the same bits. Nothing may assume they agree.** NOTHING but the three
`singleFloatReductionsAccumulateInSinglePrecision*` tests catches a regression here: every other `#f`
test input stays under `2^24`, and `ci-spec.yaml` never passes `--simd`.

## Native image, Web Image, registration

- The `native` profile passes `--add-modules jdk.incubator.vector` + `-H:+VectorAPISupport`
  (build-time only). Without the latter the Vector API falls back to per-lane emulation 6-32x SLOWER
  than scalar. GraalVM 25 refuses to combine it with `-H:+SharedArenaSupport`, which the JLine FFM
  terminal provider needs, so `JLineRepl.selectNativeImageTerminalProvider()` pins
  `org.jline.terminal.provider=jni` and the pom drops `SharedArenaSupport`; forcing
  `-Dorg.jline.terminal.provider=ffm` on the binary reproduces the old crash.
- `src/web/java/.../Target_VecSimd.java` substitutes `available()` and `install(...)`, making
  `VecSimdKernels` unreachable so the incubator module never enters the browser image. It suffices
  only because those two are the ONLY entry points; a new PUBLIC `VecSimd` method touching the
  kernels would break it, and only the Pages workflow's Web Image build would notice.
- `LispNames.VEC_PKG` + `VEC_ZEROS`..`VEC_NORM`/`VEC_MATVEC` (+ `VEC_QUALIFIED_AREF`/`_ASET`);
  `PackageRegistry.VEC_FUNCTIONS` (external, no `cl` use) + `vecFunctionNames()`;
  `resource-config.json` registers `vec.lisp` and `JvmSimdVectorTemplate.class`.

## Writing a `--simd` example or benchmark

- **`THRESHOLD = 128` is compared against the ROW LENGTH**, not the total element count, and the GEMV
  row loops have their own `MATVEC_ROW_THRESHOLD = 16`. Below those, interpreter and JVM run a scalar
  loop; wasm-GC and `--no-gc` have no threshold. `nn-vec.lisp`'s rows of 2 and 4 see nothing from
  `--simd`; `tiny-llm.lisp`'s `(vec:matvec vt a)` stays scalar until a context passes 16 tokens.
- **Print only INTEGERS.** WASM prints floats to ~7 significant digits and its `exp` differs from the
  JVM's in the low bits; `argmax` is the trick.
- `get-internal-real-time` is in MILLISECONDS -- an INTEGER on interpreter/JVM, a FLOAT on WASM --
  and `internal-time-units-per-second` does NOT exist, so an elapsed-time line must never be checked.
- `--no-gc` cannot compile `linalg:` at all (`&optional` in `linalg::%la-make`) and rejects
  `vec:matvec` in an example, so any example using either is `[interpreter, jvm, wasm]` only.
- Interpreter budget for `ExamplesE2eTest`: heat3d 0.0 s .. simd-gemv 4.7 s .. deep-digits 10.1 s ..
  tiny-llm ~13 s .. mlp 38.4 s. It fails spuriously when the GraalVM JIT prints a "Systemic Graal
  compilation failure" warning onto the program's stdout -- re-run.
- **Take every lane-kernel number under BOTH JITs.** Graal (this box's default, CI's and the
  native image's) and C2 (`-XX:-UseJVMCICompiler`, what a stock OpenJDK runs a compiled `.class`
  under). A method that overruns C2's inlining budget for a Vector API call chain gets every vector
  BOXED -- same bits, no warning, no exception, and `.todo/482` round 2 measured 1.51x under Graal
  and **0.20x** under C2 on identical arithmetic. The rule that avoids it is ONE SMALL KERNEL METHOD
  PER WIDTH: no decoder shared behind a flag, no width switch inside the lane loop. A shape that is
  fast under one JIT and boxed under the other is not done, and a number without its JIT beside it
  is not a number.
- **Benchmarking discipline.** Run benchmarks SEQUENTIALLY. Take N >= 9 samples and print them ALL
  before claiming two configurations differ (a GraalVM scalar timing turned out bimodal --
  `226 269 269 271 271 381 383 395 400`). Measure allocation with `-XX:+UseEpsilonGC -Xmx12g
  -Xlog:gc` and read heap-used-at-exit. zsh does NOT word-split an unquoted `$FLAGS`. Everything
  measured about a particular JVM belongs here, never in `doc/**` or an example header. And vary the
  axis you are not thinking about: five experiments "confirmed" GraalVM cannot vectorize because
  every one used `#f`; the first `#d` program came out 1100x faster.

## Tests

- `eval/VecSimdTest` (every kernel vs the oracle at both widths, below/above `THRESHOLD`; the bf16
  fused-equals-widened equivalence at eight shapes, the bf16 lane-count probe, the declined pairings
  and the mixed bf16/f32 element-wise VALUES; the
  `#<function vec:dot>` vs `#<lambda>` interception guard; `-into` aliasing and alias errors;
  mixed-width and rank errors), `eval/LinalgSimdTest`, `FloatWidthTest`.
- `JvmSimdAccelCompilerTest`, `JvmLinalgSimdAccelCompilerTest`, `JvmSimdModuleFallbackTest`,
  `JvmBFloat16ArrayTest` (every case on both backends; its `--simd` section pins the fused decode
  against BOTH the widened-f32 kernel and the interpreter's `--simd`),
  `JvmSimdParallelCompilerTest` (the bf16 GEMV serial == parallel == widened f32, and past the
  `--gpu` chain), and the kernels themselves in `eval/VecSimdBf16KernelsTest` /
  `codegen/jvm/JvmSimdVectorTemplateBf16Test`.
- `NoGcWasmCompilerTest`: `0xFD` presence/absence, `f64.load/store` 0x2B/0x39 and `f32.load/store`
  0x2A/0x38, `#f` narrow/widen, compile errors (mixed width, from-list, `matvec-into`),
  `{expAndSign,logAndTanh,sinCosTan}LowerNativelyOnNoGc`, and
  `intoKernelsCallTheBumpAllocatorOnlyForTheConstructors` -- `allocVec` site count 2 vs 3, matched on
  `i32.shl; i32.add; call $__ronto_alloc`, since a bare `0x10 <idx>` scan false-positives inside v128
  immediates.
- `WasmLispCompilerTest`: v128 local declarations present/absent (the local decls are the one part of
  a code section that decodes without a full opcode walker),
  `simdAppendsExactlyTheVecTypeBlockAndTheVecFunctionBlock`, `FUNC_COUNT` delta, component/optimize.
- `WasmLispCompilerIntegrationTest` (Docker+wasmtime): the `wasmGcSimd*` family
  (`IsByteIdenticalToTheScalarPathOverTheWholeVecSurface` and its Optimized/Matvec/PackedAccessor/
  IntoKernel/UnaryUfunc siblings, `PackedArraysAreCollectedRatherThanAccumulated`) plus the runnable
  dead-flag guard `wasmGcSimdModuleNeedsTheSimdProposalAndTheDefaultOneDoesNot` (`wasmtime --wasm
  simd=n --wasm relaxed-simd=n` refuses the `--simd` module at the TYPE section; relaxed-simd must
  be disabled too or wasmtime rejects the combination). The `noGcRuns*UnderBothLowerings` family
  compares `--no-gc` against a wasm-GC run, not a constant, and surfaced a `WasmTreeShaker` gap: no
  case for the `0xFD` prefix, so `--no-gc --optimize` on ANY vec program threw "unhandled opcode
  0xFD". Scalar-builtin probes `{log,tanh,sinCosTan}SoftwareApproximation`, tolerance 1e-5.
- ci-spec: `vec-kernels-cross-backend` (four backends byte-identical; f64-exact inputs so
  `mean`/`norm` land on exact doubles, plus a square and a non-square `vec:matvec`),
  `vec-destination-passing-kernels`, `comparison-select-ufuncs-cross-backend-cases`,
  `log-tanh-exact-cross-backend-cases`, `sin-cos-tan-exact-cross-backend-cases`. Run the native
  `CiSpecE2eTest` after editing any of them. `examples/ml/nn-vec.lisp` runs via `ExamplesE2eTest`.
  Manual `--no-gc`: `wasmtime run --invoke <fn> module.wasm <args>` (result on stderr).

## Not done / follow-ups

- `linalg:` acceleration is DONE: fifteen members intercepted on the interpreter, the JVM and
  wasm-GC, reusing these lane loops. One structural difference -- a linalg kernel is PARTIAL (it
  declines general arrays, mixed widths, plain numbers and shape errors by returning null, and the
  call site runs the scalar defun). `.kb/linalg-simd.md`; the width polymorphism is `.kb/linalg.md`.
- Matrix x matrix GEMM (`matmul`) is not `vec:`; it lives in `linalg:`. It needs no transpose:
  rewriting the oracle's `ijk` triple loop as `ikj` makes `b`'s rows contiguous AND preserves the
  summation order, so at `#d` the result is bit-identical; at `#f` it follows the contract above.
- `--no-gc` GEMV is DONE. Still out of scope there: rank-2 `#d`/`#f` literals, rank >= 3,
  `array-dimensions`/`array-dimension` on the matrix, and general (non-packed) rank-2 arrays.
- A stories15M-scale llama2 demo with real weights. `examples/ml/tiny-llm.lisp` is the
  real-transformer payoff at toy scale; what is left is a tokenizer and a weight loader.
