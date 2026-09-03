# 486. The backends that do not carry `bfloat16` must refuse it, not misread it

Difficulty: Medium

**Why this is Medium and not Low (2026-09-03).** Low counted the refusals. Writing a
refusal means reading an array's WIDTH, and today the width is a boolean in three places
(`.todo/687`) -- so a refusal written against a boolean is a refusal that a fourth width
falls silently past, which is the same defect `.todo/483` and `687` exist to remove. This
item therefore also introduces the width DESIGNATOR the refusals read, which `687` then
reuses for the `linalg:` widening. That is the cheaper order: paying for the right
representation once here beats deciding it twice, and `687` says so itself ("whichever
lands first should define the designator"). An `int` code with a `default:` arm would be
the trap -- it admits a third value while re-importing exactly the silence being removed.

Part of `.todo/482`, whose scope is deliberately **interpreter and JVM only**. Everything
else must say so at the point of failure. A width that silently degrades to a boxed array
on one backend and stays packed on another breaks the cross-backend identity contract
that `.kb/vec.md` and `ci-spec.yaml` exist to hold.

## Plan

Verified 2026-09-03 (by reading the tests, not by assuming): a new enum in the ROOT
`am.ik.rontolisp` package is clear of both pins. `JvmRuntimeClassFilesTest` lists only
`target/classes/am/ik/rontolisp/runtime`, and the root package is not that one.
`PackageCycleTest` reads edges from source: an enum that references NOTHING under
`am.ik.` adds no edge at all, so it cannot make a package cycle, and it stays outside the
root's one designed class cluster (hub `LispVal`). That last property is a REQUIREMENT on
the enum, not an observation -- give it a method that reaches back into the value model
and it joins that cluster. Keep it dependency-free: `code()` and `ofCode(int)`, nothing
else. Any mapping to an element-type symbol belongs on the other side.

Order, because each phase is what the next one reads:

1. **The designator.** New `FloatWidth { SINGLE, DOUBLE, BFLOAT16 }` in the root package
   with a stable `code()`; `LispFloatArray.width()` answered by each permit;
   `reader/Token`'s own `FloatWidth` retired in favour of it (`.todo/484` is right to
   land with its local one -- changing it there would cost that item a whole suite);
   `LispLexer` / `LispReader` follow.
2. **The wire.** `linalg.lisp`'s two width questions and `%la-gather-strided` carry the
   integer CODE instead of the boolean, so a kernel still reads a width without comparing
   a symbol; `LinalgSimd.gatherStrided` / `LinalgGpu.gatherStrided` convert once at the
   boundary (`args.get(4)` -> `FloatWidth.ofCode`) and switch exhaustively after that.
   Grep the ARITY, not the name, for the compiled-backend counterparts. `%la-make`'s
   decline stays until `.todo/687` gives `linalg:` the width for real.
3. **The refusals**, at the array-REPRESENTATION chokepoint rather than as a frontend
   pre-scan: the wasm-GC `$farray` `TYPE_F32ARR`/`TYPE_F64ARR` choice
   (`WasmArrayCompiler`, `WasmQuoteCompiler`), `--no-gc`'s `Ty.F32VEC`/`F64VEC`
   (`NoGcWasmCompiler`), and the component/WIT export boundary. The element type is
   decided statically at those points, which is what makes them complete; a frontend scan
   would look complete and miss whatever a fold rewrote. One shared message helper, so
   the three read identically.
4. **The tests.** A compile-refusal test per backend asserting the exact text; the
   load-bearing one asserting that every "does not yet" refusal is a `LispEvalException`
   rather than a compile error; `GpuOfferDifferentialTest`'s `Operand.single()` widened
   from a nullable `Boolean` to a nullable `FloatWidth` so its bfloat16 arm stops being
   unreachable, carrying the case this item's sharpest clause has no test for at all --
   that a `short[]` is never OFFERED to the device; and `.todo/484`'s `ci-spec.yaml` case
   declaring only the backends that carry the width.
5. **Docs.** `.kb/bfloat16.md` (the refusal rule), `.kb/vec.md` (the designator), and
   `./mvnw -Pweb compile` afterwards -- `src/web/java`'s `Target_VecSimd` substitutes a
   class whose switches this changes, and the ordinary suite never compiles it.

## Refuse, with a message that names the width

- **wasm-GC** (`WasmQuoteCompiler`, `WasmArrayCompiler`'s `$farray` struct, and the
  `TYPE_F64ARR`/`TYPE_F32ARR` pair the data field is told apart by): a `#bf16(...)` literal
  or `:element-type 'bfloat16` is a compile error.
- **`--no-gc`** (`NoGcWasmCompiler`, `Ty.F64VEC` / `Ty.F32VEC` linear-memory blocks):
  same.
- **the component / WIT path**: `bfloat16` has no WIT counterpart; refuse at the
  export boundary rather than at the first read.

The error must say the width and the backend -- "`bfloat16` arrays are supported on
the interpreter and the JVM only" -- because the failure a user will otherwise hit is a
wrong number, not a crash.

## Decline, silently and correctly

- **`--gpu`** (`LinalgGpu`, `LinalgGpuKernels`): the device kernels are f32/f64, so a
  bf16 operand declines here **until `.todo/490` lands** -- that item gives the device its
  own bf16 GEMV and supersedes this clause. Declining correctly first is what makes it a
  performance item rather than a correctness one. After
  `.todo/483` these sites are exhaustive switches, so the bf16 arm returns `null` and the
  caller falls through to the lane or defun path exactly as it already does when the
  device is absent or the matrix is too small. Check `installVec`'s `matvec` intercept and
  the residency map in particular: a `short[]` must never be *offered* to the device.
- **BLAS** (`LinalgBlas`): there is no bf16 GEMM in the intercepted set; the bf16 arm
  declines and the scalar/lane path runs.

## Verify

- A `#bf16(1.0 2.0)` program compiled with `-o x.wasm`, with `--no-gc`, and with
  `--component`: three clear errors, no output file.
- `--gpu` and `--simd` runs over a `bfloat16` array answer bit-identically to the
  plain interpreter run.
- The `ci-spec.yaml` case added by `.todo/484` must declare only the backends that carry
  the width.
