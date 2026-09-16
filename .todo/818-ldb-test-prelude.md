# `ldb-test` prelude (MISC.358 follow-up to the 037 byte family)

Difficulty: Low

`ldb-test` is the last unimplemented member of the byte family (`byte`,
`byte-size`, `byte-position`, `ldb`, `dpb`, `deposit-field`, `mask-field` all
shipped; `ldb-test` is only an `EXPORTED_ONLY` name). CLHS: `(ldb-test bytespec
int)` ≡ `(not (zerop (ldb bytespec int)))` -- T when any bit of the field is
set. Slice A precedent (`.todo/037-number-extensions.md`): one Lisp
implementation over existing primitives, so it runs on the interpreter, the JVM
and WASM-GC with no per-backend compiler.

## Implementation approach (prelude checklist, `.kb/adding-primitives.md`)

1. `LispNames.LDB_TEST` constant + move `LDB-TEST` out of
   `PackageRegistry.CL_EXPORTED_ONLY` into `CL_FUNCTIONS` (978 externals
   unchanged, pinned by `PackageRegistryTest`).
2. `LispPreludeLibrary.SOURCES` defun: `(defun ldb-test (bytespec integer)
   (not (zerop (ldb bytespec integer))))`. No `Environment` entry, no
   per-backend compiler, no wrapper entry (first-class free, like `float-sign`).
3. no-GC: explicit compile-time refusal beside `RATIONALIZE`
   (`NoGcWasmCompiler.collectCallsCons`) -- the `ldb` expansion reads the
   bytespec cons back, which the scalar value model has no representation for
   (the `deposit-field` precedent: clean refuse, never a trap).
4. Pins: `LispEvaluatorTest#evalLdbTest`,
   `JvmLispCompilerTest#compileAndRunLdbTest`,
   `WasmLispCompilerIntegrationTest#ldbTest` (via `compileAndRunPrelude`),
   `NoGcWasmCompilerTest#rejectsLdbTest`, ci-spec `byte-ldb-test`.
5. Docs EN/JA per-operator page + `_catalog.yaml` entry + `cl.md` row, verified
   by `DocExamplesTest` (+ `./mvnw -f docs-tool/pom.xml test` for layout).
6. Effect measured as a diff of failing ANSI test NAMES before/after
   (`ansi-test/measure.sh numbers misc`): expect MISC.358 fixed, 0 regressed.

## Traps

- `deposit-field` ≠ `dpb` (037 banner): `ldb-test` must go through `ldb`, not a
  hand-rolled `dpb` spelling -- `(logbitp i newbyte)` vs `(logbitp (- i pos)
  newbyte)`. Using `ldb` directly dodges the whole family of mistakes.
- WASM-GC pins stay in the exactly-representable range (i31 components,
  `.kb/wasm-bignum.md`); bignum fields trap fail-stop like `(/ a b)` of the
  same integers -- no new failure mode.
