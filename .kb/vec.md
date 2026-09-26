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
- **`coerce` and `concatenate` build one too**, from a result-type designator naming the width
  (`(coerce seq '(vector single-float))`, `(concatenate '(array bfloat16) ...)`) -- the same door
  `make-array` opens, through the shared `%seq-float-vector` helper
  ([concatenate-result-families.md](concatenate-result-families.md)). It was NOT one until
  2026-09-06: the result-type normalizer carried an integer width beside the family, so every
  float width silently answered a general vector -- or, when the source was already packed, the
  ARGUMENT UNCHANGED (`.todo/707`). `eval/PackedFloatReachabilityTest` now walks the permits
  through that door too.

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
- The `%la-gather-strided` wire carries `%la-dropout-mask` too, since 2026-09-06 (`.todo/687`): both
  travel as `FloatWidth.code()` and BOTH readers of the mask's argument tested it for NULLNESS, which
  a boxed `Long 0` passes. Grep the ARITY of a member on this wire, never its name.
- **The one width test that cannot be a compile error** is the NAME -> width direction: a new
  width's name is new source text, and no switch can demand it. It is now DERIVED, not
  transcribed: `LispFloatArray.widths()` holds one zero-length prototype per permit and
  `LispFloatArray.prototypeFor` resolves a `:element-type` designator (quoted or bare,
  qualifier stripped) by matching the permits' own `elementType()` answers, so the name
  `make-array` accepts is by construction the name `array-element-type` reports back. Every
  consumer -- `eval/Environment`'s allocation, the JVM/WASM make-array gates, the `--no-gc`
  type pass and emitter and its `vec:` constructor interception, `upgradedArrayElementType`
  (the `typep` upgrade), and the `%print-object-str` vector arm's exclusion list -- switches
  over what `prototypeFor` answers with NO `default` arm, so a permit added without an arm
  at any of them is a compile error (483's probe re-run 2026-09-05: 100 sites). The table is
  the single hand-written point; `eval/PackedFloatReachabilityTest` pins it against
  `getPermittedSubclasses()` and walks every permit through every door (make-array,
  `array-element-type`, `typep`, `vec:zeros/ones/arange` and `vec::%make-like` via `vec:add`,
  the printer exclusion) -- which is the loud failure the compiler cannot give. A door loop
  must iterate the PERMITS, never `WIDTHS` itself: a missing row would otherwise shrink the
  loop silently, reproducing the boxed fallback inside the test that exists to catch it.

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
`BuiltinFunctionWrappers.WASM_UNSUPPORTED` -- every transcendental built-in compiles on WASM,
since 2026-09-17 as a call into the fdlibm runtime (`WasmFdlibmRuntimeBuilder`), the same
algorithm the interpreter and the JVM run as `StrictMath`, so the digits are one set on every
backend (`.kb/transcendentals.md`). `(sin -0.0)`/`(tan -0.0)`/`(tanh -0.0)` are `-0.0` (odd);
wasm `signum` maps `-0.0`/NaN to `0.0`.

- **The oracle is each backend's OWN scalar defun** (the emap rule: read widened to f64, apply the
  backend's scalar op, narrow on store), so cross-backend `-0.0`/NaN output stays OUT of ci-spec;
  the transcendental digits themselves are one set everywhere since 2026-09-17
  (`.kb/transcendentals.md`). fdlibm's `exp` answers `0.0` below the smallest denormal, which is
  what makes a `-infinity` mask reach `linalg:softmax` as `0.0` (`.kb/linalg.md`); the
  `--simd`/`--no-gc` kernels call the same function, so they stay bit-identical to the defun.
- **Lane forms only where they equal the defun.** Interpreter/JVM and wasm-GC lane-ize sqrt, abs,
  negative and reciprocal only (`VectorOperators.EXP` is not bit-identical to `StrictMath.exp`;
  gate `JvmSimdVectorTemplate.hasLaneForm`); `sqrt`'s element function is the float-domain square root
  (`vec::%fsqrt`, `linalg::%la-fsqrt` beside it): NaN on a negative input on both paths. CL `sqrt`
  roots negatives into the complex plane, and a complex has no packed element store, so the CL
  spelling signalled on the scalar path (a type error on the interpreter/JVM, a trap on wasm-GC)
  while the lane answered NaN. **`log`, `asin` and `acos` joined it on 2026-09-11**, when CL's
  own spellings gained the same escape (`.kb/jvm-complex.md`, "Real arguments that leave the real
  domain") -- `%flog`/`%fasin`/`%facos` and their `%la-` twins are NaN outside the real domain, and
  the rule is now general: an element function that can answer a complex does not belong in a
  packed kernel. Scalar `(sqrt x)` itself stays complex-extended; only the packed
  element functions are float-domain, pinned by `ci-spec.yaml`'s `vec-sqrt-negative-cross-backend`
  (four backends x scalar/`--simd`, with 200-element lane shapes); the transcendental ufuncs and
  sign walk element loops over `WasmVecSimdRuntimeBuilder.emitScalarUnaryF64` (a call into the
  fdlibm runtime, or the inline `emitSignumF64`), which `NoGcWasmCompiler.compileSimdUnaryF64`
  reuses, so BOTH `--no-gc` lowerings emit the identical loop (no `0xFD`). All f32 lane forms are
  exact by the `53 >= 2*24+2` bound. Since 2026-09-17 the scalar `(exp x)`/`(log x)`/etc.
  builtins join `--no-gc` too, one call each into the same fdlibm runtime the `vec:` kernels
  above call (`NoGcWasmCompiler.compileTranscendentalUnary`/`compileLog`/`compileAtan`, the
  pre-scan extended in `collectFdlibmRoots`), with `expt` only over the FLOAT lattice point
  (`.kb/transcendentals.md`).
- New v128 opcodes (`f32x4/f64x2.sqrt/abs/neg/lt/gt`, `v128.bitselect`) go in
  `am.ik.wasm.Instruction` AND `WasmSections.skipSimd` (which throws on unknown 0xFD).

## The four acceleration layers

Layers 0-3 are `--simd` and TOTAL -- one lane kernel per member. Layer 4 is PARTIAL, over the GEMV
pair only.

**A width mismatch DECLINES at every layer, and signals at none** (.todo/686 for layer 0, .todo/720
for layers 1 and 3): `vec.lisp`'s `%map2` reads every operand through `aref`, which widens whatever
the packed storage width is, so the scalar defun computes a mixed `#f`/`#d` (or bf16-beside-either,
outside the one fused pairing below) pair happily -- `--simd` may not turn that answer into an
error. Each layer declines in the shape its own call site allows, and all three are pinned together
by `ci-spec.yaml`'s `vec-mixed-width-declines-cross-backend` over the `--simd` axis (below), which
is what makes them one rule rather than three:

- **Layer 0** rides `VecSimd`'s `defineFn` protocol (below): every width-mismatch arm across the
  sixteen element-wise/reduction/GEMV members answers `null` instead of throwing, so the captured
  scalar binding runs.
- **Layer 1** decides BEFORE the call. `JvmSimdCompiler.emitLaneWidthGuard` already asked each array
  operand whether it was a `double[]` or a `float[]`; it now asks them as one -- the first operand
  picks the width and every other must match -- and a failing test takes the same fallback branch
  the `_simdReady()` degrade uses, into the spliced defun. The bridge is TOTAL over what the guard
  admits, so `JvmSimdVectorTemplate.mixedWidth()` survives only as its own defensive contract and
  is unreachable from a compiled call site.
- **Layer 3** decides INSIDE the helper, because its call site is a bare `call` with no fallback
  arm: `requireSameKind`'s mismatch arm forwards to the scalar defun (`declineToScalar`: a null env
  then the helper's own params, which ARE the defun's) and returns its answer, instead of
  `unreachable`. `WasmVecSimdCompiler.scalarFallbacks` hands `build` the defun's function index per
  helper; `-1` (no such defun in the program) keeps the trap, which no call site can reach, since a
  call site is what keeps the defun reachable.

Layer 2 (`--no-gc`) never had the hole: it types the packed widths apart, so a mixed call is the
COMPILE error `incompatible types F32VEC and F64VEC` -- a diagnostic, not a surprise at runtime.

**Layer 0, interpreter `--simd`** (jdk.incubator.vector): the eight vectorizable kernels run on
`eval.VecSimdKernels`. The DEFAULT interpreter is unchanged -- it is the cross-backend oracle, and
`ci-spec.yaml`'s scalar pass never passes `--simd` (a SECOND pass does, and both check the same
expected lines -- "The E2E `--simd` axis" below). `eval.VecSimd.available()`/`install(Environment)` are the ONLY
callers of the kernels; `LispEvaluator.setSimd(true)` installs in `resolveFunction`'s lazy-load hook
(so the REPL is accelerated too) and `RontoLispCli`'s `enableSimd` probes `available()` first
(absent module -> a one-line note + the scalar reference) -- UNLESS `--parallel` is also given: then
an absent module is a hard error instead (`.todo/700`: the scalar fallback split across threads is a
~100x slowdown from the accelerated kernel, which under a long-running program's own output reads as
a hang, and the one-line warning is easy to miss under it -- `--parallel` is asked for only by
someone about to run something large, unlike bare `--simd`, which stays an ordinary decline). The
compiled `.class` output (`_simdInit`'s `LinkageError` catch, layer 1 below) keeps degrading either
way -- it may run on a different, module-equipped machine LATER, where the interpreter's process IS
the machine it will run on.

**Layer 1, JVM `--simd`**: `JvmSimdCompiler` (from `JvmExprCompiler`, gated
`usesSimd = simdAccel && programUsesAnyAcceleratedSimdOp` -> `Ctx.simdOps`) rewrites the call sites
to a `<Program>$SimdBridge` shipped beside the class (`JvmSimdRuntimeBuilder` renames
`JvmSimdVectorTemplate.class` after the program and emits `_simdInit`, like the `java:` bridge).
Because `mean`/`norm` always call `sum`/`dot`, ANY `--simd` program using the package ships the
bridge. Running it needs
`java --add-modules jdk.incubator.vector`; the default build is byte-identical. **Module-absence
degrade**: `_simdInit` CATCHES the `LinkageError`, warns once on stderr, leaves `_simdAvailable`
false, and every call site checks `_simdReady()` before falling back to the defun -- the same
degrade `--blas`/`--gpu` give with no library/device.

**bfloat16 rides layers 0 and 1 only, over TWO pairings**: `sum` / `dot` / `matvec` /
`matvec-into` fuse a bf16 DECODE into the lane loop when the operand at the weight position is
`#bf16` and every other array operand is `#f` -- bf16 weights against f32 activations, the
pairing the plan has -- and the element-wise members with a single-float lane loop
(`add` / `sub` / `mul` / `div` with the CL spellings, `sqrt` / `abs` / `negative` /
`reciprocal`, all with `-into` siblings) fuse over bf16 x bf16 -> bf16 (`.todo/747`). Every
other member and every other pairing DECLINES to the scalar defun, INCLUDING a mixed bf16/f32
element-wise call, which `--simd` used to raise the fixed-width error on and which the defun
computes happily; a flag may not turn an answer into an error. The fused reduction answer is
the f32 kernel's over the widened operand BIT FOR BIT, so the width joins the reduction
contract below rather than adding one of its own; the fused element-wise answer is the
defun's bit for bit. Mechanics, the decline sites and the cache-resident cost:
`.kb/bfloat16.md`.

**A Q8_0 quantized matrix rides layers 0 and 1 too, in `matvec` / `matvec-into` only**, against an
`#f` or `#d` vector: the integer-dot kernel, which is the defun BIT FOR BIT (no lane-count pin, no
threshold -- an integer sum is exact in any fold), so the width has no entry in the reduction
contract at all. Everything else declines it to the defun. `.kb/quantized-matrix.md`.

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
  `count`, so the padding lanes are zero and every kernel folds them harmlessly -- harmlessly to the
  VALUE. Not to the BITS, and that is where wasm-GC parts company with the other three at a length
  that is not a multiple of the lane count (`.todo/758`, closed as a contract exception -- see
  the reduction contract above for both answers).
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
  all the index arithmetic assumes. A mixed-width call forwards to the scalar defun
  (`requireSameKind` -> `declineToScalar`, above): a helper's params are the defun's, one null env
  short, which is what makes the hand-back a plain forwarding `call`.
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
  shipped green. Both halves of that matrix now exist: the unit half here, and the E2E half as
  `CiSpecE2eTest`'s `Accel` axis (below), which is red on this exact defect. `WasmVecLoops` holds the linear v128 bodies, the scalar ones AND the GC group bodies
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
examples pinning derived integers (`simd-gemv`, `tiny-llm`, `llm`) are RUN under the flag.

## The f32-reduction precision contract

Every `--simd` backend accumulates an f32 reduction in f32 and promotes ONCE at the value boundary.
At lengths that are multiples of the lane count all four agree; at any other length the two
folds below may differ in the last bit (`.todo/758`, closed as a contract exception -- unifying
them is not worth the blast radius). The scalar `vec.lisp` reference stays the more accurate
f64-accumulating oracle. `#d` is untouched.

- **The lane-count pin.** An f32 reduction's value depends on the lane count (`2^24 + 768` at 4
  lanes, `+ 896` at 8, `+ 960` at 16), so `FSPECIES_REDUCE` is `FloatVector.SPECIES_128`, not
  `SPECIES_PREFERRED`, in BOTH `eval.VecSimdKernels` and `JvmSimdVectorTemplate` -- a compiled class
  must not answer differently on an AVX-512 host, and the WASM kernels are always `f32x4`.
  Element-wise f32 kernels and f64 reductions keep `SPECIES_PREFERRED`. A `#bf16` operand decodes
  into those same four lanes (`ShortVector.SPECIES_64` -> `IntVector.SPECIES_128`, pinned for the
  same reason) and accumulates in f32, so every probe below transfers verbatim to that width -- 2^24
  and 1.0 are both exact in bfloat16. The element-wise bf16 kernels are bit-exact at any lane
  count, so they run at `SPECIES_PREFERRED` like the f32 element-wise ones. The two kernel files mirror
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
- **The region BETWEEN the two gates -- 16 to 31 columns, lanes and one chain -- is pinned on all
  four implementations and in the corpus**, because nothing else reaches it: every other `vec:` case
  is 2 to 6 elements, the greedy-decode cases are 128 and 64 columns, and an example touches 16-31
  only transiently, as an attention `V^T . att`'s sequence length grows through it. The unit pin is
  the 2^24 probe at 24 columns (six whole lane groups, `2^24 + 3*6 = 16777234` everywhere); the E2E
  pin is `simd-gemv-below-the-accumulator-gate-cross-backend`, integer-exact so both corpus passes
  check one set of lines. 31 columns is pinned too, and answers `2^24 + 24` on all four -- but that
  agreement is arithmetic luck at a PARTIAL group, not the contract; see the bullet below.
- **Whether the gate belongs at 32 or higher was re-taken on a model, and it is a wash.** The clean
  per-process probe says four chains LOSE at 48 columns (0.96x Graal / 0.89x C2 on the GB10, and
  0.74-0.97x on x64), so stories15M's attention GEMVs would rather have the single chain; three
  builds of one tree differing only in this constant decode stories15M at 343.9 (gate off) / 393.1
  (32) / 402.6 (96) tok/s, so the gate is worth 1.13x and moving it to 96 is 1.001x with the spread
  across 1.0. Numbers, harness and the whole argument:
  `.todo/artefacts/480-the-simd-gemv-row-is-one-accumulator-chain/README.md`.

**A length that is NOT a multiple of the lane count is outside the agreement above (measured
2026-09-10, `.todo/758`, closed as a contract exception).** The lane COUNT is pinned; the way
the LAST, partial group is closed is pinned as DIFFERENT BY DESIGN. The interpreter, the JVM
class and `--no-gc` run the lane loop to `loopBound(n)` and add the leftover as a scalar tail
in index order; wasm-GC (and the component, which wraps the same core module) has no scalar
tail at all (the packed layout below) and folds `ceil(n/4)` groups with the padding lanes
zeroed. Both are exact-arithmetic equivalents and neither is the other's bits: a 1x31 `#f`
GEMV with `2^24` at column 29 answers **16777244** on the interpreter, the JVM and `--no-gc`
and **16777248** on wasm-GC and the component, and `vec:dot` over 131 elements with `4096.0`
at index 127 answers 16777344 against 16777348 (the scalar oracle answers 16777246 and
16777346 -- exact). Nothing caught it because every cross-backend `--simd` probe uses a
multiple of 4 (1024 for the reductions, 16/24/32 for the GEMV gate), which is exactly where
the two strategies coincide. Unifying was considered and rejected: giving wasm-GC a scalar
tail costs it the property the padded layout was built for (the `+1` sentinel group, the
shuffle window and `gcSaveLastGroup` all assume the padded shape), giving the other three a
padded final group means materialising a zeroed group or masking on the hot path -- all to
move a last bit that has shipped since the wasm-GC kernels landed and has never moved a
model output (greedy argmax absorbs it, as `.todo/480` re-verified on eight legs).
`partialFinalGroupFoldsTheScalarTailByDesign` (interpreter, JVM) and
`wasmGcSimdPartialFinalGroupFoldsTheZeroPaddedGroupByDesign` (wasm-GC, component, `--no-gc`,
scalar oracle) pin both answers, so a future change to either fold is visible.

The pinning probe: `v = #f(4096.0 1.0 ... 1.0)`, 1024 elements -- a multiple of the lane
count, so both folds coincide and every `--simd` backend must agree. `dot(v,v) = 4096^2 + 1023 = 16778239`
exactly; `4096^2` is `2^24`, where the f32 spacing is 2, so the lane holding it swallows every `1.0`
while the other three lanes fold 256 ones each.

| probe | scalar (all backends) | `--simd` (all backends, multiple-of-4 lengths) |
|---|---|---|
| `(round (vec:dot v v))`, `v[0] = 4096.0` | 16778239 | **16777984** |
| `(round (vec:sum v))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (aref (vec:matvec m v) 0))`, 1x1024 | 16778240 | **16778176** |
| any of the above at `#d` width | 16778239 | 16778239 |

The GEMV row groups as sixteen lanes (`2^24 + 960`); `vec:dot`/`vec:sum` keep one four-lane chain
(`2^24 + 768`). **A GEMV row and a `vec:dot` over the same two vectors are the same value
mathematically and NOT the same bits. Nothing may assume they agree.** The
`singleFloatReductionsAccumulateInSinglePrecision*` tests catch a regression of the
multiple-of-4 agreement, and the `partialFinalGroup*` tests catch one of either partial-group
fold: every other `#f` test input stays under `2^24`.

**This is also the one thing a ci-spec case may not do.** The corpus runs twice, scalar and
`--simd`, against ONE set of `expected:` lines with no per-pass override, so a case whose `#f`
reduction crosses `2^24` would make the two passes disagree and the axis would read that as a
defect. Such a probe belongs in `VecSimdTest`, where the two answers can both be written down.

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
- **The second JIT cliff is a CONVERSION, and it is Graal's.** `convertShape(VectorOperators.I2D,
  DoubleVector.SPECIES_128, part)` -- four int lanes into two double lanes -- is not intrinsified
  by Oracle GraalVM 25.0.4: a GEMV using it ran at 0.02 Gelem/s, a thousand times slower than the
  same integer work with a horizontal reduce, and 250x slower than the shipped kernel, with no
  warning (2026-09-05, `.todo/672`'s README; C2 ran the same code at 5 Gelem/s). What to use
  instead: convert int lanes to f32 with `convert(VectorOperators.I2F, 0)` (same shape, exact
  below 2^24) and accumulate in an f32 vector; a scalar oracle can still mirror that bit for bit,
  because 53 >= 2 * 24 + 2 makes a double op rounded once the f32 op (`.kb/quantized-matrix.md`).
  Never assume a lane conversion is intrinsified: time it under both JITs before building on it.
- **The third JIT cliff is a PART, and it is C2's.** `convertShape(conv, species, part)` with
  `part != 0` is not an instruction: `AbstractVector.convertShapeTemplate` spells it
  `slice(origin)` then the part-0 conversion, and `sliceTemplate` is an iota shuffle, a compare,
  a second shuffle and two `rearrange`s blended -- Java that C2 compiles as written (~4 cycles a
  slice) and Graal folds into the widening instruction's upper-half form. The Q8_0 GEMV's first
  kernel did six a block and ran at 0.7x of f32 under C2, 1.45x under Graal; the same bits with
  64-bit loads (part-0 widens only), the activation pre-widened and the upper half of a product
  vector brought down by a constant half-swapping `rearrange` run at 1.9x / 1.45x (2026-09-06,
  `.todo/706`'s README). Widen from the NARROWER species as part 0; never write a part-1
  conversion in a lane loop. Two more C2 facts from the same item: a kernel's compile has
  `NodeCountInliningCutoff` (18000 nodes, a stock default) as its size budget and the Vector API
  spends hundreds of nodes a call, so two rows a pass or two blocks an iteration ran BOXED at
  0.1x with `-XX:+PrintInlining` naming the loop body's last calls (`NodeCountInliningCutoff`);
  and a helper C2 has compiled standalone for one caller is refused inlining into another once
  its code exceeds `InlineSmallCode` (1000 bytes on aarch64; a boxed Vector API method always
  does), "already compiled into a big method" -- so a kernel's helpers are private to their one
  caller, and a shape harness times one shape per JVM.
- **Benchmarking discipline.** Run benchmarks SEQUENTIALLY, and time kernel SHAPES one per JVM
  (above). Take N >= 9 samples and print them ALL
  before claiming two configurations differ (a GraalVM scalar timing turned out bimodal --
  `226 269 269 271 271 381 383 395 400`). Measure allocation with `-XX:+UseEpsilonGC -Xmx12g
  -Xlog:gc` and read heap-used-at-exit. zsh does NOT word-split an unquoted `$FLAGS`. Everything
  measured about a particular JVM belongs here, never in `doc/**` or an example header. And vary the
  axis you are not thinking about: five experiments "confirmed" GraalVM cannot vectorize because
  every one used `#f`; the first `#d` program came out 1100x faster.

## The E2E `--simd` axis

`CiSpecE2eTest` crosses the four backends with two `Accel` legs -- the default kernels and
`--simd` -- so every corpus case and every `standalone:` case runs eight ways instead of four.
Both legs check the SAME `expected:` lines (see the `2^24` rule above).

**Why the whole corpus rather than the cases that touch packed floats.** `--simd` is not a faster
route to the same code, it is a different data REPRESENTATION: a packed `#f`/`#d` array is a
`TYPE_VBLOCK` of v128 groups instead of an `$f32arr`/`$f64arr`, and every reader and writer of one
has to know. Which primitives touch it is not enumerable by eye -- the `widen-float-bits` /
`narrow-float-bits` trap shipped green with ci-spec cases, unit tests on all four backends and a
green native run, and was found by an unrelated lane running an example. A subset chosen by
judgement rebuilds exactly that hole one level up.

**Measured 2026-09-06** (this box, native binary, 479 corpus cases; "fixed" is a five-form
program, "marginal" is `(corpus - fixed) / 479`):

| leg | fixed | corpus | marginal per case |
|---|---|---|---|
| interpret | 16 ms | 7117 ms | 14.8 ms |
| compile-jvm | 170 ms | 9212 ms | 18.9 ms |
| run-jvm | 90 ms | 3541 ms | 7.2 ms |
| compile-wasm | 157 ms | 4254 ms | 8.5 ms |
| run-wasm | 89 ms | 3624 ms | 7.4 ms |
| compile-component | 155 ms | 4531 ms | 9.1 ms |
| run-component | 69 ms | 4320 ms | 8.9 ms |

**The premise that this run is fixed-cost-dominated is WRONG**: 746 ms of fixed cost across the
four backends against 479 x 74.8 ms = 35.8 s of marginal, i.e. 2% of a pass. So a second pass
really does cost a second pass, and the per-case `simd:` flag it rules out is worse than doubling
rather than better -- the corpus is concatenated into ONE program per backend, so a per-case flag
means one program per case: 479 x 746 ms ~ 6 minutes of process starts, per pass. Whole suite:
**65.5 s -> 111.9 s** (2008 -> 4020 tests). Under 2x only because the same change dropped a
duplicated compile: each WASM leg now inspects the module it is about to run
(`requireRunnableModule`) instead of compiling a throwaway `guard.wasm` first.

**`--simd` FAILS OPEN, so passing the flag is not evidence it did anything.** The CLI's
`enableSimd` and the emitted `_simdInit` both warn on stderr and run the scalar kernels when
`jdk.incubator.vector` is off the module graph, and the program's stdout is then byte-identical --
measured, not assumed. `CiSpecE2eTest.assertSimdTookEffect` therefore asserts two things no output
can show: the degrade warning is ABSENT from stderr (interpreter and JVM, the only paths with a
fallback), and the emitted artifact DIFFERS from the scalar leg's (the JVM class and both wasm
modules). The JVM leg's `java` launcher carries `--add-modules jdk.incubator.vector`, as
`ExamplesE2eTest`'s does.

Verified by breaking it three ways (2026-09-06), each with everything else left alone:

- `.todo/692`'s bug reintroduced (`WasmFloat16Compiler`'s `if (ctx.simd)` forced false): RED on
  `WASM --simd` and `WASM_COMPONENT --simd` (the run traps, exit 134) plus the
  `widen-narrow-float-bits` standalone case on both. All eight scalar-leg cells green, no example
  touched.
- the JVM launcher's `--add-modules` removed: RED on `JVM --simd` only -- 3 cells, and all 479
  output comparisons still green, which is the fail-open this guards.
- the binary replaced by a `java -jar` wrapper without `--add-modules`: RED on `INTERPRETER --simd`
  only (1 + 22 standalone).

**A green `--simd` leg was, until 2026-09-06, a green SCALAR leg on the JVM family.** The kernels
gate on LENGTH -- `THRESHOLD = 128` on the element-wise members and the reductions,
`MATVEC_ROW_THRESHOLD = 16` and `MATVEC_ACC_THRESHOLD = 32` on the GEMV -- and every `vec:` /
`linalg:` case in `ci-spec.yaml` was 2 to 6 elements long, so on the interpreter and JVM legs all of
them took the scalar fallback and the flag changed nothing but the emitted artifact (wasm-GC and
`--no-gc` have no threshold, so their v128 paths did run, which is why the axis still caught
`.todo/692`). The two greedy-decode cases added for `.todo/705` --
`transformer-greedy-decode-text-cross-backend` and
`gated-delta-rule-greedy-decode-text-cross-backend` -- are the corpus's first shapes above every
gate: element-wise operands of 128 and GEMV rows of 128 and 64, including the four-accumulator
chain, decoded to TEXT so a moved argmax fails loudly rather than shifting a digit
(`.kb/test-execution.md`, "Decoded text and the argmax alarm").

**`--no-gc` and `--parallel` are NOT axes here, for different reasons.**

- `--no-gc` lowers a small subset of the language and REFUSES the rest, so it cannot compile the
  corpus at all -- there is no second pass to run. Its axis is which programs it refuses and with
  what message, which is a different question and lives in `NoGcWasmCompilerTest` /
  `WasmLispCompilerIntegrationTest`'s `noGcRuns*UnderBothLowerings` family.
- `--parallel` changes reduction ORDER, not representation. It splits the rows of kernels that
  `--simd` already selected, adds no new data layout, and touches nothing outside those kernels, so
  its blast radius is enumerable -- unlike `--simd`'s, which is every reader of a packed array.
  It is pinned where that radius is (`JvmSimdParallelCompilerTest`: serial == parallel == widened;
  the `parallel: true` example). It also requires `--simd`, is REFUSED on both wasm backends, and
  would double only the two JVM-family legs. Revisit if a `--parallel` defect ever lands outside
  the kernels.

## Tests

- `eval/VecSimdTest` (every kernel vs the oracle at both widths, below/above `THRESHOLD`; the bf16
  fused-equals-widened equivalence at eight shapes, the bf16 lane-count probe, the fused
  element-wise kernels vs the defun, the declined pairings
  and the mixed bf16/f32 element-wise VALUES; the
  `LispFunction`-vs-`LispLambda` interception guard (the printed text no longer tells the
  pair apart -- both answer `#<function VEC:DOT>`); `-into` aliasing and alias errors;
  mixed-width and rank errors), `eval/LinalgSimdTest`, `FloatWidthTest`.
- `JvmSimdAccelCompilerTest`, `JvmLinalgSimdAccelCompilerTest`, `JvmSimdModuleFallbackTest`,
  `JvmBFloat16ArrayTest` (every case on both backends; its `--simd` section pins the fused decode
  against BOTH the widened-f32 kernel and the interpreter's `--simd`, and the fused
  element-wise kernels against the defun on all three legs),
  `JvmSimdParallelCompilerTest` (the bf16 GEMV serial == parallel == widened f32, and past the
  `--gpu` chain), and the kernels themselves in `eval/VecSimdBf16KernelsTest` /
  `codegen/jvm/JvmSimdVectorTemplateBf16Test`.
- `NoGcWasmCompilerTest`: `0xFD` presence/absence, `f64.load/store` 0x2B/0x39 and `f32.load/store`
  0x2A/0x38, `#f` narrow/widen, compile errors (mixed width, from-list, `matvec-into`),
  `{expAndSign,logAndTanh,sinCosTan,arcAndHyperbolic}LowerNativelyOnNoGc` (the fdlibm
  coefficients present in the code section, no `0xFD` opcode in the user's body -- the byte does
  occur inside fdlibm's immediates), and
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
  0xFD". Scalar-builtin probes `{log,tanh,sinCosTan}SoftwareApproximation` keep their
  historical tolerance shape over the whole call path; the bits are
  `WasmFdlibmRuntimeBuilderTest`'s.
- ci-spec: the whole corpus runs on four backends x {default, `--simd`} (the axis above), plus
  `vec-kernels-cross-backend` (four backends byte-identical; f64-exact inputs so
  `mean`/`norm` land on exact doubles, plus a square and a non-square `vec:matvec`),
  `vec-destination-passing-kernels`, `simd-gemv-below-the-accumulator-gate-cross-backend` (the
  16-31 column region above), `comparison-select-ufuncs-cross-backend-cases`,
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
