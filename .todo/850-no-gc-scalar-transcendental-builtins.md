# --no-gc: the scalar transcendental builtins are one call away now

Difficulty: Low

`.kb/vec.md` records that `(exp x)`, `(log x)`, `(sin x)` etc. "remain unknown on
`--no-gc`": only the `vec:` kernels reach them, through the fdlibm runtime the module
carries (`NoGcWasmCompiler.fdlibmFunctions`, `Mem.fdlibmIndex`, 2026-09-17,
`.kb/transcendentals.md`). A scalar site is now a `call` into the same function over an
f64 -- the FLOAT lattice point -- so the eleven unary names, `(atan y x)`,
`(log n base)` and the float `expt` can join the operator set without a new lowering.
What has to be decided:

- the pre-scan (`fdlibmFunctions`) must count these call sites too, and place the
  trig tables when `sin`/`cos`/`tan` are reached;
- a pure-numeric module today carries no linear memory; `sin`/`cos`/`tan` need the
  tables blob, so such a program would gain a memory section (the other functions do
  not need one);
- an exact integer argument coerces through the INT -> FLOAT promotion the lattice
  already has; a negative `log`/`asin` argument answers NaN (no complex tier here),
  which `doc/en/guides/wasm-nogc.md` must state.

Pin with `NoGcWasmCompilerTest` structure probes and a `WasmLispCompilerIntegrationTest`
`--invoke` case comparing against a wasm-GC run.
