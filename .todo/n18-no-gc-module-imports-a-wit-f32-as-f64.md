# `--no-gc` module: a WIT `f32` import is an `f64` import

Difficulty: Low

`wit-import` of `f: func(x: f32) -> f32` under `-o m.wasm --no-gc` (no `--component`) writes
`:params '(:float) :returns :float`, which the scalar backend imports as
`(func (param f64) (result f64))`. A host implementing the WIT (`f32`) does not link:
wasmtime 51 `--preload calc=h.wat`: `types incompatible: expected type (func (param f64)
(result f64)), found type (func (param f32) (result f32))`. Measured 2026-11-28 from a Lisp
`wit-import` and a Java `@WitImport` alike (the directive is shared).

`--no-gc --component` already refuses `f32` and `char` at the WIT line
(`WitImportDirective.requireScalarWitType`). Decide: refuse on the module too (the component's
set), or import an `f32` as `f32` (demote/promote at the wrapper, as `%single-float` does).

- First a failing test: `NoGcWasmCompilerTest` (a refusal) or `NoGcWasmImportE2eTest` (the
  node host, if `f32` is carried).
- Then `doc/{en,ja}/java/wit.md` ("What each output takes", the `--no-gc` module row) and the
  Lisp `wit-import` page if it lists the types.
