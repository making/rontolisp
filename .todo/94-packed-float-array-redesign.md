# 94 — Clean redesign: a dedicated packed-float array type (supersedes todo 92's shadow)

This is the from-scratch design to re-implement `simd:` (and forward-compatible
`linalg:`) acceleration WITHOUT the `.todo/92` `double[]`-shadow hack. Implement this on a
fresh branch off `develop` (the todo-92 shadow is a durable WIP checkpoint on
`worktree-simd-nogc-poc`, commit `5b1b065`, kept only as reference — do NOT build on it).

## Approved decisions (2026-07-08, user sign-off — do NOT re-litigate)

1. **`#f` becomes `double-float`-typed** — storing a non-real is a type error (a breaking
   change from today's `t`-typed `#f`; heterogeneous `#f` no longer works). APPROVED.
2. **rank-n packed numeric**, NOT vector-only — so linalg matrices share the representation
   (`#f((..)(..))` is a real matrix everywhere). APPROVED.
3. **The packed representation is always-on** (unboxed storage regardless of `--simd`);
   `--simd` / native `v128` only add the SIMD-lane layer on top. APPROVED.

## Why redo it (the design smell in todo 92)

todo 92 bolted a vector optimization onto the **general heterogeneous array** runtime: a
`double[]` cached in a 4th header slot. That coupling forced three avoidable complications:
a **degrade path** (a non-`Double` store into a `t`-typed array must un-pack), a
**shadow-vs-displaced discriminator collision** in the header, and a **rank-1-only** gate.
All of it exists only because `#f`/simd values were kept as general arrays.

**Decision:** make a packed numeric array a **distinct first-class type**, not a variant of
the general array. Specialize away from the heterogeneous array (the user's instinct), but
at rank-**n** granularity (not vector-only) so `linalg` matrices ride the same rails.

## The type

A **packed float array** (`float-array` / `fvec`): rank-n, `element-type double-float`,
stored as a flat `double[]` + `int[] dims`. Unboxed on every backend. Distinct from the
general boxed/heterogeneous array.

- **Element type = `double-float`.** Storing a real coerces to double; storing a non-real
  is a **type error**. → the degrade path DISAPPEARS by construction (its ugliest part).
  This makes `#f` genuinely `(array double-float)` and unifies with `--no-gc` (which already
  can't store non-doubles in `#f`). It IS a behavior change: today `(setf (aref #f(1.0) 0)
  "x")` works; after, it errors. Document it.
- **Rank-n.** rank-1 = vector (the common/optimized case, `#(...)` printed); rank-2 = matrix
  (`#2A(...)`, for linalg); rank-n allowed. `#f((1.0 2.0)(3.0 4.0))` is a matrix natively on
  every backend (today it is JVM/interp-only and a `--no-gc` compile error).
- **No fill-pointer / adjustable / displaced on packed arrays.** They are pure compute
  buffers; `(make-array … :element-type 'double-float :fill-pointer/:adjustable/:displaced-to)`
  falls back to the general boxed array. This is the big simplification: the entire
  fill-pointer/displaced/adjustable surface (the scariest machinery) is UNTOUCHED — a packed
  array is always plain.
- **Interop** (dispatch packed-vs-general): `aref`/`aset`/`length`/`array-rank`/
  `array-dimensions`/`row-major-aref`/`print`/`prin1`/`princ`/`equal`/`equalp`/`coerce`/
  `to-list`/`map`. Printing is defined by dims+elements → **identical bytes to a general
  double array**, so cross-backend output is unchanged.
- **Predicates:** `arrayp` = t, `vectorp` = t iff rank 1, `array-element-type` =
  `double-float`; internal `%float-array-p`.

## Two layers (keep them separate)

1. **Packed representation = unboxed storage, ALWAYS ON** (not gated on `--simd`). A packed
   array simply avoids per-element boxing. This alone recovers most of the win.
2. **SIMD vectorization = an opt-in layer on top.** `--simd` (JVM) / native `v128`
   (`--no-gc`) route the vectorizable kernels to lane loops over the packed `double[]`. The
   bridge's `unbox` is now trivial (the array IS a `double[]` — no shadow logic).

## Per-backend representation

- **Interpreter:** `record LispFloatArray(double[] data, int[] dims) implements LispVal`.
  `aref` → `LispDouble`; `aset` coerces to double. simd/linalg run the portable scalar
  defuns over it (fast enough; the interpreter is not the perf path).
- **JVM:** recommended repr `Object[]{ int[] dims, double[] data }`, discriminated by
  `arr[0] instanceof int[]` (disjoint from cons/funref `Object[]` whose `arr[0]` is a LispVal
  / `Integer`, and from the general array `ArrayList`). No embedded class needed for the
  array itself (the Vector-API bridge class stays). **Touch-point:** every `Object[]`
  discriminator (`consp`, `atom`, funref checks) must also exclude `arr[0] instanceof int[]`.
  `aref` boxes on read, `aset` coerces; the `jdk.incubator.vector` bridge reads `data`
  directly (zero copy). (Alternative: a small embedded wrapper class `RontoLispFloatArray`
  for clean `instanceof` discrimination at the cost of a defineClass — weigh it.)
- **wasm-GC:** GC structs `$farray = struct { (ref $i32arr) dims, (ref $f64arr) data }`,
  `$f64arr = (array (mut f64))`. Unboxed f64 storage (a real win over the general array's
  boxed `eqref` elements). `aref` boxes on read. SIMD lanes: `v128` does not load from GC
  arrays, so the kernel is an **unboxed scalar loop** over the `f64` array (still a big win);
  a true `v128` path would need a linear-memory copy — defer/skip. This finally brings the
  `--no-gc` packed benefit to wasm-GC.
- **`--no-gc`:** unify with the existing `F64VEC` (`[count][f64…]` linear block + native
  `v128`). rank-1 is essentially done. rank-n (linalg matrices): either extend the block
  header to carry dims, or exploit the backend's static type inference to keep shape a
  compile-time property over a flat `[count][f64…]`. Land rank-1 unification first; rank-n on
  `--no-gc` can stay a clear compile error initially (matrices JVM/interp/wasm-GC-only).

## Kernels / libraries

- Keep `simd.lisp` / `linalg.lisp` as portable scalar references, but make their
  `make-array` calls `:element-type 'double-float` so they PRODUCE packed arrays. Other
  backends run the scalar defuns over the packed repr (unboxed); JVM `--simd` and `--no-gc`
  intercept the vectorizable kernels.
- Kernels to accelerate: simd `add/sub/mul/scale/dot/sum` (+ mean/norm transitively);
  linalg `add/sub/mul/div/emap/dot/sum/mean/norm/amax/amin` (rank-1) and `matmul/outer`
  (rank-2). Determinism note (reduction associativity → last-ULP) carries over verbatim.

## Reuse vs discard from the todo-92 WIP (commit 5b1b065)

- **Reuse:** `JvmSimdVectorTemplate` Vector-API kernels (unbox/box become trivial),
  `JvmSimdRuntimeBuilder`/`JvmSimdCompiler` call-site interception, **the `CliOptions.--simd`
  fix** (still needed — cherry-pick it early), the determinism analysis, and the test
  scenarios (mutation / reuse / matrix / cross-backend).
- **Discard:** the shadow-in-header machinery, `_ensureBoxed`/degrade, the displaced
  discriminator changes, the rank-1 gate, the `usesSimd` gating of the array runtime.
  `JvmArrayRuntimeBuilder` goes back to UNCHANGED (packed is a separate repr).

## Risks / must-verify

- **Dispatch surface:** aref/length/print/equal/coerce/map/arrayp/vectorp/consp handle two
  reprs on every backend — the main cost, but each site is a clean type check (the todo-92
  audit already mapped this surface). Missing one = a wrong-type read, caught by tests.
- **Cross-backend byte-identical output** on all 4 backends + native `CiSpecE2eTest` (add a
  matrix case). Printing is dims+elements → should be identical.
- **Behavior change:** `#f` is now `double-float`-typed (non-real store errors); heterogeneous
  `#f` no longer works. Update docs (`data-types.md`, `.kb/simd.md`).
- **A timing benchmark** (chained + matmul hot loops) — the win is chains/reuse/matrices.

## Suggested implementation order (new session)

1. Frontend: `LispFloatArray` value + reader `#f`→packed (rank-n) + printer/equal/resolver.
   Interpreter aref/aset/length/print/equal/to-list. Green `LispEvaluatorTest`.
2. JVM: `Object[]{int[],double[]}` repr + dispatch (+ `consp`/funref exclusions) +
   `make-array :element-type double-float`; then the simplified `--simd` Vector-API bridge.
   Green `JvmLispCompilerTest` + a new `JvmFloatArray*Test`.
3. wasm-GC: packed GC struct + dispatch. wasmtime integration tests.
4. `--no-gc`: unify with `F64VEC` (rank-1 first; rank-n later).
5. `simd.lisp`/`linalg.lisp` → `:element-type double-float`; wire the linalg bridge.
6. `ci-spec.yaml` (+ matrix case) + all-4-backend manual verify + benchmark + docs.

## Pointers

- WIP reference: commit `5b1b065`, `.kb/simd.md` ("Unboxing mitigation"), the todo-92
  audit of the JVM array surface. Frontend: `LispArray`/`reader`. Backends:
  `JvmArrayRuntimeBuilder`, `WasmLispCompiler`, `ScalarWasmCompiler` (`F64VEC`), `LispEvaluator`.
  Libraries: `SimdLibrary`/`simd.lisp`, `LinalgLibrary`/`linalg.lisp`. `.todo/93` (linalg
  acceleration) folds into this — build linalg on the packed type directly.

## Progress log (branch feat/packed-float-array, off develop)

- **Phase 0 DONE** — branched `feat/packed-float-array` off `develop`; re-applied ONLY the
  `CliOptions.noValueKeys += "--simd"` fix + `CliOptionsTest` (not the shadow). Green.
- **Phase 1 DONE (frontend + interpreter)** — `LispFloatArray(double[] data, int[] dims)`
  record added to the `LispVal` seal; printing delegates to a throwaway boxed `LispArray`
  so it is byte-identical to a general double array. Reader `#f(...)` (rank-n, numpy-style
  inference) now builds `LispFloatArray` (the develop reader gained the shared
  `readGroupedElements`/`arrayDimensions`/label refactor). Interpreter dispatch (Environment):
  `make-array :element-type 'double-float` (no fill-pointer/adjustable/displaced) -> packed;
  `aref`/`%aset`/`row-major-aref`/`%row-major-aset` coerce-to-double + type-error on non-real;
  `array-dimensions`/`array-element-type`(->double-float)/`length`/`%arrayp`/`vectorp` dispatch
  both reprs; fill-pointer/adjustable/displacement predicates give the simple-array answer for
  packed, mutators error via `requireGeneralArray`. `LispNames.DOUBLE_FLOAT` added.
  Tests: `LispFloatArrayTest` (13). Full suite green (2863, skip 2 = WASM Docker).
  - Interpreter deferred/known-gaps — RESOLVED (CLI-verified interp + JVM byte-identical):
    `coerce #f(..) 'list` works; `typecase`/`(array ...)`/`(vector ...)` over packed (incl.
    rank-2) → arr/vec correctly. `map` over a packed array is unsupported, but `map` over a
    GENERAL array is ALSO unsupported (map takes list inputs only: "car expects a cons cell")
    — pre-existing, packed matches general, out of scope.
- **Phase 2 DONE (JVM real rank-n packed `double[]`, Option X) — the 2a placeholder is GONE.**
  `#f(...)` and `make-array :element-type 'double-float` now compile to a native `double[]`
  with the header `[rank, dim..., data...]` (offset `1+rank`); NO general-array fallback at any
  rank. New `JvmFloatArrayRuntimeBuilder` emits gated `_fv*` dispatch helpers (`_fvToGeneral`,
  `_fvAref1/2/N`, `_fvAset1/2/N` (coerce via `_dbl`, return the stored Double like the interp),
  `_fvDims`, `_fvLength`, `_fvMake`, `_fvElementType`), each `instanceof double[]` → packed else
  delegating to the general `_array*`/`_length` helper. `usesFloatArray` gate (AST walk:
  `#f` literal OR make-array double-float) threaded through `Ctx`/`Builder`; op-compilers route
  through `_fv*` only when set (`JvmArrayCompiler`, `JvmLengthCompiler`, `JvmArraypCompiler` +=
  `instanceof [D`, `array-element-type` → `_fvElementType`). Print: `_lispToString`/
  `_lispToDisplayString` gained an `instanceof double[]` branch → `_arrayToString(_fvToGeneral)`.
  `JvmAsm` gained double/`double[]` ops (`newarrayDouble`/`daload`/`dastore`/`dload`/`dstore`/
  `i2d`/`d2i`/`l2d`/`dadd`/`dmul`/`ldc2Double`…). `JvmFloatArrayTest` extended to 16 (rank-3 aref,
  non-double store coerces, setf returns the coerced double, rank-2 setf, element-type=double-float,
  non-real store = ClassCastException). Full suite green (2879, skip 2 = WASM Docker). **CLI-verified
  byte-identical interpreter vs JVM** across literal/make/aref/aset/length/dims/array-rank/
  array-total-size/array-element-type/vectorp/svref/coerce→list/reduce. Default build (no packed
  arrays) unchanged (623 JvmLispCompilerTest green).
  - **`arrayp` quirk RESOLVED (out of scope):** `(arrayp x)` and `(typep x ...)` are undefined as
    standalone functions on BOTH backends for a GENERAL array too — pre-existing (they are only
    reachable via type-specifier expansion → `%arrayp`), NOT a packed regression. `%arrayp`/
    `vectorp`/`svref`/`coerce`/`typecase` (the real surface) all work over packed.

### Remaining: REAL rank-n packed on every backend (the core deliverable)

Interpreter (Phase 1) is already correct rank-n packed. Redo JVM as real packed, then the
other backends — NO general-array fallback anywhere (rank-n packed is decision #2).

- **Phase 2 REDO (JVM real rank-n packed `double[]` + `--simd`) — repr DECISION = "Option X",
  user-approved. STATUS: packed repr DONE (see "Phase 2 DONE" above); the `--simd` accel layer
  (impl step (e) below) is the ONLY remaining part of Phase 2, deferred to run alongside Phase 5
  (simd.lisp must produce packed arrays to exercise it).** JVM packed = a **bare `double[]` with an embedded dimension header**, laid
  out `[rank, dim_0, ..., dim_{rank-1}, e_0, ..., e_{total-1}]` (rank & dims stored as doubles,
  exact for realistic sizes). Data offset `off = 1 + rank` (= 2 for a rank-1 vector); total =
  product(dims). Rank-N capable. **Why X over the design's `Object[]{int[],double[]}` (Y) or a
  wrapper class (Z):** a `double[]` is NOT an `Object[]` subtype, so cons/funref/ratio/general-
  array discriminators (`JvmConspCompiler`/`JvmAtomCompiler`/`JvmFunctionpCompiler`/
  `JvmEqGeneralCompiler`/`JvmEvalRuntimeBuilder`/`JvmEmitHelper` listSlot) are **UNTOUCHED** —
  zero risk of a packed array being misread as a cons (Y's scary failure mode). No defineClass
  (Z's cost). **ZERO-COPY confirmed (the reason X is acceptable):** `jdk.incubator.vector`
  `DoubleVector.fromArray(SP, arr, off+i)` / `intoArray(arr, off+i)` read/write the backing
  `double[]` DIRECTLY at the header-shifted offset — the header only shifts the base offset by a
  constant, never forces a copy. `simd:dot`/`sum` read via offset loads + a scalar tail
  `a[off+i]`, no copy; element-wise ops allocate ONE fresh `double[off+n]` result (semantically
  required, = WIP's `box`), no input copy; chains reuse the intermediate directly. The only copy
  is the rare `java:`-interop boundary (strip header to hand a bare `double[]` to Java).
  - **Impl order (JvmArrayRuntimeBuilder stays UNTOUCHED — packed is a separate repr):**
    (a) literal codegen: `JvmQuoteCompiler`/`JvmExprCompiler` `case LispFloatArray` → emit a
        `double[1+rank+total]` (`NEWARRAY T_DOUBLE`; `DUP; iconst k; ldc2_w <double>; DASTORE`
        for rank, each dim, each element). Add a shared emitter.
    (b) new `JvmFloatArrayRuntimeBuilder` emitting gated `_fv*` static helpers, each taking the
        array as `Object`, dispatching `instanceof double[]` → packed (header-aware) else
        delegating to the existing general `_array*` helper: `_fvAref1`/`_fvArefN` (Horner flat
        index over header dims, box the double), `_fvAset1`/`_fvAsetN` (coerce val→double via a
        `coerceDouble(Object)` — Long/Double/BigInteger/ratio(BigInteger[2]) → double, else
        type error — returns the stored double boxed), `_fvRowMajorAref/Aset` (flat at off+idx),
        `_fvLength` (rank-1 → total, rank-n → sequence error like general), `_fvDims` (cons list
        of Longs from header), `_fvToString(Object,boolean escape)` (render `#(...)`/`#nA(...)`
        byte-identically), `_fvMake(dims, init)` (Long or list-of-Longs → packed double[] filled
        with coerceDouble(init), default 0.0), `_fvArrayp`, `_fvVectorp`, `_fvElementType`
        (double[] → symbol "double-float").
    (c) `usesFloatArray` gate (AST walk: a `#f`/LispFloatArray literal, OR make-array with
        `:element-type 'double-float`, OR an accelerated `simd:` op). Thread through `Ctx` like
        the WIP's `usesSimd`; emit the `_fv*` methods only when set.
    (d) op-compiler dispatch: when `ctx.usesFloatArray`, route through the `_fv*` wrappers;
        else call the general helper directly (byte-identical default build). Sites:
        `JvmArrayCompiler` (aref/aset/make/dims/row-major), `JvmLengthCompiler` (length),
        `JvmArraypCompiler` (%arrayp), the `VECTORP`/`ARRAY_ELEMENT_TYPE` cases in
        `JvmExprCompiler`, `JvmPrintCompiler` (print/prin1/princ → `_fvToString`); coerce/map
        expand to aref/length so they inherit the dispatch.
    (e) **`--simd` accel layer** (opt-in): port `git show 5b1b065:.../JvmSimdVectorTemplate.java`
        (+`JvmSimdCompiler`/`JvmSimdRuntimeBuilder`) — DROP all shadow logic; `unbox` = use the
        packed `double[]` directly with `off = 1 + (int)a[0]`; `box` = new `double[off+n]` with
        header written. Gate on `--simd` + program-uses-accelerated-simd-op. Run a `--simd`
        class with `java --add-modules jdk.incubator.vector Prog`. **VERIFY the bridge is
        actually embedded + a real timing delta** (dead-flag lesson [[simd-shadow-and-dead-flag-lesson]]).
- **Phase 3 (wasm-GC) — REAL rank-n packed (no general fallback).** GC struct `$farray =
  struct{(ref $i32arr) dims, (ref (array (mut f64))) data}`; unboxed f64 (a real win over the
  boxed-eqref general array); simd = a scalar loop over the f64 array (v128 doesn't load GC
  arrays). wasmtime 46 + docker are LOCAL, so verify.
  - **WASM-GC map (Explore, 2026-07-08) — the exact anchors to touch:**
    - Value model = `(ref null eq)`; fixnums `i31ref`, else GC struct/array. General array =
      `TYPE_CELL`(5) box → `TYPE_CONS`(3) header `(dims . (meta . data))`; `dims`/`data` =
      `TYPE_HASH_BUCKETS`(34) = `array (mut (ref null eq))`; `meta` = `(fp . (adj . offset))`.
      "is an array" = `ref.test $cell` + header CAR is `TYPE_HASH_BUCKETS` (vs i31 = hash table).
    - Type constants declared near `WasmLispCompiler.java:440-559` (hand-assigned sequential
      indices). Types emitted in `WasmLispCompiler.compile` `writeTypeSection(...)` `:1402-1616`;
      `TYPE_HASH_BUCKETS` as a bare array comptype `:1573-1577`, `TYPE_STR_BYTES`(36)=`array (mut i8)`
      `:1591-1595` (the template for a new `array (mut f64)`). **ADD NEW TYPES AT THE END** — export/
      import wrapper type indices are computed as `TYPE_WRITE_STR_GC + 1`-based (`:542-543`), so a
      fixed type inserted BEFORE them shifts those. am.ik.wasm API: bare comptype via
      `CountingDef.add(Consumer<WasmWriter>)`; struct via `RecTypeDef.addSubFinalStruct` +
      `StructFieldWriter.addField(mutable, Consumer<WasmWriter>)`. GC opcodes: `ARRAY_NEW`0x06,
      `ARRAY_NEW_FIXED`0x08, `ARRAY_GET`0x0B, `ARRAY_SET`0x0E, `ARRAY_LEN`0x0F, `STRUCT_NEW`0x00,
      `STRUCT_GET`0x02, `REF_TEST`0x14, `REF_CAST`0x16, `I31_REF_NEW`0x1C, `I31_GET_S`0x1D.
    - Ops are ALL INLINE in `WasmArrayCompiler.java` (NO helper functions like the JVM `_array*`):
      `compileMake`:50 (data via `array.new $hash_buckets`:78), `compileAref`:406, `compileAset`:508,
      `compileRowMajorAref`:419, `compileRowMajorAset`:436, `compileDims`:458; flat index
      `emitFlatIndex`:578; displacement walk `emitResolveDataAndIndex`:531; inline helpers
      `arrayNew`/`arrayGet`/`arraySet`:1042-1057, `castBuckets`:1060, `getData`:1080, `getMeta`:1088,
      `boxI31`:1037 (all hard-wired to `TYPE_HASH_BUCKETS`). Literal: `WasmQuoteCompiler`
      `compileLiteralArray`:34 → `compileQuotedArray`:92. Front-end dispatch `WasmExprCompiler.java`
      :411-441 (+ bare literal `case LispArray`:72 — ADD `case LispFloatArray`).
    - Discriminator sites to add a `ref.test $farray` branch: `WasmArraypCompiler.java:60-61`,
      `WasmLengthCompiler.java:102-106`, `WasmRuntimeBuilder.emitPrintArray`:2265 (tests :2274-2282;
      per-element callback `elementFunc`=`FUNC_PRINT_VAL`/`FUNC_PRINC_VAL`, stride
      `emitPrintArrayStride`:2541; print tokens `vecPrefix`"#(" / `hashPrefix`"#" / `rankAOpen`"A(").
    - `array-element-type`/`vectorp`/`svref`/`array-rank`/`-dimension`/`-total-size`/`coerce` are
      LispMacroExpander lowerings recompiled (`WasmExprCompiler` :420-442); `array-element-type`
      expands to `(progn arr t)` (same lite-`t` issue as JVM — needs a packed branch for double-float).
    - **Types + `emitPrintArray` are UNCONDITIONAL (NOT gated like the JVM `usesArrays`)** — only the
      fill-pointer wrapper defuns gate on `programUsesAnyArrayOp` (`:888-893`). So the `$farray`/`$f64arr`
      types + the packed print branch can be emitted always (cheap); no `usesFloatArray` gate needed on
      WASM. `programUsesAnyArrayOp`:2204, `programContainsArrayLiteral`:2231 (already fire on packed? verify).
    - Integration test `WasmLispCompilerIntegrationTest.java` (Docker-gated `@Testcontainers`,
      `wasmtime --wasm gc`); structural unit test `WasmLispCompilerTest.java`.
  - **Design DECISION: distinct `TYPE_FARRAY` struct (JVM-parallel, matches the user's "distinct
    first-class type" instinct), NOT a reuse of the general cell shell.** `TYPE_F64ARR = array (mut f64)`
    (data), `TYPE_FARRAY = struct{(ref null eq) dims (a $hash_buckets of i31 sizes), (ref null eq) data
    (a $f64arr)}`. Disjoint from `TYPE_CELL` → every "is an array" site adds a simple `ref.test $farray`
    OR; every element site adds a `ref.test $farray` packed branch (element boxed via `struct.new $float`
    from the f64).
  - **CHUNK A DONE (types + literal + print) — VERIFIED on wasmtime.** Added `TYPE_F64ARR`(39)/
    `TYPE_FARRAY`(40) constants (`WasmLispCompiler.java`) + emitted them in `writeTypeSection` right after
    type 38, and shifted the export/import wrapper bases `wrapperTypeIndex`/`importTypeIndex` from
    `TYPE_WRITE_STR_GC + 1` to `TYPE_FARRAY + 1` (2 sites). `WasmQuoteCompiler.compilePackedLiteral`
    builds the farray (f64arr data via `array.new $f64arr` + `array.set`, i31 dims buckets, `struct.new
    $farray`); wired `case LispFloatArray` in `WasmExprCompiler` (bare) + `compileQuotedVal` (quoted).
    PRINT: `WasmRuntimeBuilder.emitPrintArray` gained a prologue that, when `ref.test $farray`, converts
    the farray to an equivalent general `TYPE_CELL` (reusing its dims buckets, boxing each f64 into a
    `TYPE_FLOAT` element) and stores it back into param 0, then reuses the whole general renderer — NO
    dedicated packed print path, NO new FUNC_* index (component blobs untouched). Helpers `farrayData`/
    `farrayDims` added. **CLI-verified interp == wasm-GC byte-identical** (`#f(1.0 2.0 3.0)`→`#(1.0 2.0
    3.0)`, `#f((1.0 2.0)(3.0 4.0))`→`#2A((1.0 2.0) (3.0 4.0))`, `#f(1 2 3)`→`#(1.0 2.0 3.0)`). Full unit
    suite green 2879; 8 general-array wasmtime integration tests green (print prologue doesn't touch
    general arrays — fires only on `ref.test $farray`).
  - **CHUNK B DONE (wasm-GC ops) — VERIFIED on all 4 backends (2026-07-08).** Inlined `ref.test $farray`
    packed branches in `WasmArrayCompiler`: `compileAref` (Horner via new `emitPackedFlatIndex` reading
    `farray.field0` → `array.get $f64arr` → box `$float`), `compileAset` (subscripts→i31 flat, coerce
    val→f64 via `castFloatGetF64`, `array.set $f64arr`, return the boxed coerced float), `compileRow
    MajorAref/Aset`, `compileDims` (small farray/general branch for the dims buckets, shared cons-build
    loop), `compileMake` (new `compilePackedMake`: `emitParseDims` + `array.new $f64arr` of coerce(init),
    default 0.0; a double-float array WITH fp/adj falls back to general defaulting init to 0.0 like JVM).
    New `compileElementType` (packed→symbol `double-float` via `compileStringLiteral`, else `emitTrue`),
    wired into `WasmExprCompiler` `ARRAY_ELEMENT_TYPE` (replacing the `(progn arr t)` expansion). Both
    discriminators: `WasmArraypCompiler` (outer `ref.test $farray`→1), `WasmLengthCompiler` (rank-1
    farray → `dims[0]` i31, rank>1 traps). New helpers `testFarray`/`emitIfEq`/`farrayField`/`getFarray
    Data`/`f64Array{New,Get,Set}`/`boxFloat`/`f64Const`/`isDoubleFloatElementType`. **Value→f64 = the
    existing `WasmEmitHelper.castFloatGetF64` (handles i31/ratio/float; non-real → `ref.cast $float` trap =
    type error) — no new helper needed.** No `usesFloatArray` gate on WASM (farray types + inline ops are
    unconditional). **Verified:** interpreter/JVM/wasm-GC/wasm-component all byte-identical (aref/aset+int→
    double coercion/make/rank-2 fill/length/element-type/row-major-aref/coerce→list/typecase array+vector/
    dot-product loop). Full suite **2879** green; WASM Docker integration **581** green + **9 new packed
    tests** (`WasmLispCompilerIntegrationTest.compilePackedFloat*`); native `CiSpecE2eTest` **720** green
    incl. the new `packed-float-arrays-cross-backend` ci-spec case (all 4 backends). **`arrayp`/`vectorp`/
    `typep` as STANDALONE function calls remain undefined (pre-existing, fails identically on general
    arrays) — use the `array`/`vector` type SPECIFIER in `typecase`/`typep-macro`.** **`reduce`/`map` over
    a packed array error — but they error IDENTICALLY over a GENERAL array (`(reduce #'+ (make-array 3))`
    → same ClassCastException/"non-empty sequence"; `map` → "car expects a cons cell"): they are
    list/string-only in rontolisp, NOT a packed gap. Packed is fully consistent with general arrays.
    Workaround: `(reduce #'+ (coerce v 'list))` (coerce→list works). Making reduce/map accept arrays at
    all is a separate, backend-wide feature (Phase 6 stretch, not blocking).**
  - **CHUNK B mechanics (discovered this session — exact anchors + gotchas):**
    - GOTCHA: general aref/aset/rowmajor/dims begin `compileExpr(arr); castCellGet0(ctx)`, and
      `castCellGet0` = `ref.cast $cell` which TRAPS on a non-cell. So the packed branch MUST run BEFORE
      it: compile the array into a temp, `ref.test $farray` -> `IF` packed `ELSE` general (current body).
      Model on the `emitPrintArray` prologue already written (same test+branch shape).
    - Horner: `WasmArrayCompiler.emitFlatIndex(ctx, headerSlot, args, firstSub, rank)` (:578) reads each
      dim `getLocal(headerSlot); castConsGet(0); castBuckets; i32.const k; array.get $hash_buckets;
      castI31GetS`. Packed variant: read the dim sizes from `farray.field0` (cast `$hash_buckets`)
      instead of the header -- store `farray.field0` into a slot first.
    - packed aref has NO displacement/fill-pointer -> SKIP `emitResolveDataAndIndex`: just
      `array.get $f64arr (farray.field1 cast $f64arr, flat)` then `struct.new $float`. packed aset:
      Horner -> coerce val->f64 -> `array.set $f64arr` -> return the boxed float (interp returns the
      COERCED double; matches JVM `_fvAset*`).
    - value->f64 coercion (aset/make init): `WasmEmitHelper.castFloatGetF64(ctx)` unboxes a
      `TYPE_FLOAT`->f64 but needs a float first. Reuse the `float` builtin's number->`TYPE_FLOAT`
      conversion (`WasmFloatConvCompiler`, `LispNames.FLOAT`) then `castFloatGetF64`, OR find the direct
      WASM number->f64 helper. Non-real -> trap ("type error"). `WasmQuoteCompiler` already has private
      `getF64Arr`/`f64ArrayNew`/`f64ArraySet` to mirror.
    - discriminators: `WasmArraypCompiler` (:22, general = 3-level cell/cons/header-car-buckets) ->
      prepend `ref.test $farray` -> emitTrue. `WasmLengthCompiler` (array branch :102) -> prepend
      `ref.test $farray` -> rank-1 count = `farray.field0[0]` i31 (rank>1 traps like general).
      `array-element-type` expands to `(progn arr t)` -> add a WASM packed check returning the symbol
      `double-float` on `ref.test $farray` (like JVM `_fvElementType`; no `usesFloatArray` gate on WASM,
      just always emit the check). `make-array :element-type 'double-float` w/o fp/adj/displaced ->
      build a farray at runtime (dims + array.new $f64arr filled with coerce(init), default 0.0).
    - `programContainsArrayLiteral` (:2231) gates only fill-pointer wrapper defuns; a `#f`-only program
      calling aref works WITHOUT a gate change (inline ops; farray types + print unconditional). Verify
      `vectorp`/`svref` (expand via `%arrayp`) on a `#f`-only program still emit what they need.
- **Phase 4 (`--no-gc`) — real `F64VEC` + native `v128`.** No general array type here, so there
  is no fallback anyway. Port `ScalarWasmCompiler` F64VEC (`git show 5b1b065`, ~857-line diff):
  rank-1 first (design: "essentially done"); rank-n = a CLEAR COMPILE ERROR initially (an explicit
  error, NOT a silent fallback). This is where the native-SIMD win lives.
- **Phase 5 (libraries)** — `simd.lisp`/`linalg.lisp` `make-array ... :element-type 'double-float`
  so they PRODUCE packed arrays; wire the JVM `--simd` interception + `SimdLibrary`/`RontoLispCli`
  gates. NOTE: `linalg.lisp` uses `:initial-element 0` (INTEGER) today → packing changes linalg
  output int→double; reconcile existing linalg doc/tests carefully.
- **Phase 6 (verify)** — ci-spec MATRIX case + native `CiSpecE2eTest` (all 4 backends byte-identical),
  chained + matmul benchmark, docs (`data-types.md` — `#f` is `double-float`, non-real store errors;
  `.kb/simd.md` rewrite to the packed design), `-Pweb compile`, javadoc. Also finish the interpreter
  full-pipeline gaps (`coerce`/`map`/`typep` over packed).

### Resumption facts (post-compaction)

- **Branch `feat/packed-float-array` off `develop` (440056c). Phase 1+2+3 COMMITTED at `f3d5ccd`**
  (the branch's first commit; working tree clean afterwards). Full suite green: 2879, skip 2 (WASM
  Docker). Phase 4+ work starts fresh on top of `f3d5ccd`.
- **Done:** Phase 0 (CliOptions `--simd` + `CliOptionsTest`), Phase 1 (frontend + interpreter REAL
  rank-n packed: `LispFloatArray`, reader `#f`, Environment dispatch, `LispNames.DOUBLE_FLOAT`;
  `LispFloatArrayTest` 13), **Phase 2 (JVM real rank-n packed `double[]`, Option X — see "Phase 2
  DONE" above; `JvmFloatArrayTest` 16; CLI-verified interp==JVM byte-identical).** Remaining:
  Phase 3 (wasm-GC), Phase 4 (--no-gc), Phase 5 (libs), Phase 2C (`--simd` accel, with Phase 5),
  Phase 6 (verify). NEXT UP: Phase 3 (wasm-GC packed struct) — advances the core cross-backend
  deliverable; `--simd` (2C) deferred to pair with Phase 5.
- **New files:** `LispFloatArray.java`, `codegen/jvm/JvmFloatArrayRuntimeBuilder.java`, tests
  `CliOptionsTest`/`LispFloatArrayTest`/`JvmFloatArrayTest`, this `.todo/94`. **Modified:**
  `LispNames`, `LispVal` (seal += LispFloatArray), `CliOptions`, `reader/{Token,LispLexer,LispReader}`,
  `eval/{Environment,LispEvaluator}`, `codegen/jvm/{JvmAsm,JvmArrayCompiler,JvmArraypCompiler,
  JvmLengthCompiler,JvmExprCompiler,JvmQuoteCompiler,JvmRuntimeBuilder,JvmLispCompiler}`,
  `codegen/wasm/{WasmLispCompiler (types 39/40 + wrapper base shift),WasmQuoteCompiler
  (compilePackedLiteral),WasmExprCompiler (case LispFloatArray),WasmRuntimeBuilder (emitPrintArray
  farray prologue + farrayData/farrayDims helpers)}`.
- **Verify recipe (all 4 backends, from a scratchpad `.lisp`):** interp `java -jar $JAR f.lisp`;
  JVM `cd $dir && java -jar $JAR f.lisp -o Prog.class && java Prog` (the `-o` name MUST be path-free —
  the class is named after it); wasm-GC `java -jar $JAR f.lisp -o f.wasm && wasmtime run -W gc f.wasm`;
  component `--component` + `wasmtime run -W gc=y -W component-model-async=y ...` (see CLAUDE.md).
  wasmtime 46.0.1 + docker are RUNNING locally. Docker integration tests: `WasmLispCompilerIntegrationTest`.
- **Reuse (git):** `git show 5b1b065:<path>` for `JvmSimdVectorTemplate`/`JvmSimdCompiler`/
  `JvmSimdRuntimeBuilder` (bridge, drop shadow) and `ScalarWasmCompiler` (F64VEC). `git show
  21fb03e:<path>` for `simd.lisp`. The WIP's `usesSimd` gate pattern lives in
  `git show 5b1b065:.../JvmLispCompiler.java`.
- **`arrayp` quirk RESOLVED:** confirmed pre-existing — `(arrayp x)` and `(typep x ...)` are
  undefined as standalone functions for a GENERAL array too (only reachable via type-specifier
  expansion → `%arrayp`). Out of scope; `%arrayp`/`vectorp`/`svref`/`coerce` work over packed.

### JVM low-level emission mechanics (discovered this session — avoid re-surveying)

- **`JvmAsm`** (`codegen/jvm/JvmAsm.java`) is a high-level one-pass bytecode assembler with
  symbolic labels (`label()`/`bind()`/`branch(op,label)`) and helpers `aload/astore/iload/
  istore/iconst/iinc/dup/pop/anew/anewarray/checkcast/instanceOf/invokestatic/-virtual/-special/
  getstatic/putstatic/aaload/aastore/arraylength/ldcString/areturn/ireturn/op(int)/u2(int)`.
  Use this to hand-write the `_fv*` helpers (much nicer than raw opcodes). It does `finish()`
  → `List<Integer> code`.
- **`JvmAsm` LACKS all double / double-array ops** — must ADD (the WIP added ~28 lines to JvmAsm):
  `newarray double` (`NEWARRAY` opcode + atype byte 7), `daload`(0x31)/`dastore`(0x52),
  `dload`/`dstore`(with slot), `dreturn`, `i2d`(0x87)/`d2i`(0x8e)/`l2d`(0x8a), `dadd/dmul/dsub`,
  `ldc2_w`(0x14, a `DoubleConstant` via `cp.addDouble(v)`), `dcmpl/dcmpg`, `dup2`. Opcodes exist
  in `am.ik.jvm.Opcode` (DALOAD/DASTORE/NEWARRAY/LDC2_W/I2D/D2I/L2D/DCONST_0/1/DLOAD/DSTORE/…).
- **Method finalization:** a helper method = `new ArrayMethod(cp.addUtf8(NAME), cp.addUtf8(DESC),
  maxStack, maxLocals, m.finish())` — **the caller supplies maxStack/maxLocals MANUALLY** (JvmAsm
  does NOT compute them; e.g. AREF1 is `(…,3,2,…)`). Count carefully (a double occupies 2 stack
  slots and 2 local slots). Mirror `JvmArrayRuntimeBuilder.build(...)` structure (a `List<
  ArrayMethod> build(cp, objectClass, objectArrayClass, selfClass)` that JvmLispCompiler emits).
- **General helper name/desc constants to DELEGATE to** (all in `JvmArrayRuntimeBuilder`, `selfClass`
  static methods, gate `usesArrays`): `MAKE`/`AREF1`/`AREF2`/`AREFN`/`ASET1`/`ASET2`/`ASETN`/`DIMS`/
  `TO_STRING`(`_arrayToString`,`(Ljava/lang/Object;)Ljava/lang/String;`)/`TO_DISPLAY_STRING`/`RM_GET`/
  `RM_SET`/… — so `_fvX(Object arr,…)` does `if (arr instanceof double[]) <packed> else <invokestatic
  the general helper>`. A double array `x instanceof double[]` uses `INSTANCEOF` with a
  `cp.addClass("[D")` class constant.
- **Push a boxed Double:** `JvmEmitHelper.compileDouble(v, ctx)` = `DCONST_0/1` or `LDC2_W <double>`
  then `invokestatic Double.valueOf`. For the RAW double in a `double[]`, skip the box.
- **`ctx.doubleValueOf`** (Double.valueOf) and friends are on `Ctx`; the array runtime is emitted via
  `JvmArrayRuntimeBuilder.build(...)` results appended in `JvmLispCompiler` around line ~840
  (`usesArrays`); mirror that to append `JvmFloatArrayRuntimeBuilder.build(...)` when `usesFloatArray`.
- **`ratio` on the JVM = `BigInteger[2]`** (matters for `coerceDouble`: Long/Double/BigInteger/
  BigInteger[2]→double, else type error). Reuse/mirror the existing numeric→double coercion if one
  exists (search `JvmArithmetic*`/`asDouble`), else write it in the `_fv*` builder.
- **Print dispatch:** the central value→string path invokes `_arrayToString` when `instanceof
  ArrayList`; add a sibling `instanceof double[]` → `_fvToString` (simplest `_fvToString`: build a
  general ArrayList-array from the header+data like a runtime `toGeneralArray`, then call
  `_arrayToString` — reuses the renderer instead of re-implementing `#(…)`/`#nA` formatting). Find
  the dispatch near `arrayToStringMethod`/`arrayToDisplayStringMethod` refs in `JvmLispCompiler`
  (~line 853) and `JvmPrintCompiler`.
