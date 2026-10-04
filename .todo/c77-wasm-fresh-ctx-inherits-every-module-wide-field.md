# c77. `WasmAsyncEmit.freshCtx` should inherit every module-wide `Ctx` field by construction

Difficulty: Medium

`freshCtx` rebuilds a `WasmLispCompiler.Ctx` field by field, and it builds the SYNCHRONOUS top
level of every program, not only async chunks. A field it forgets silently takes the builder
default there, so a form answers differently at the top level than inside a defun. That has
been fixed one field at a time at least nine times (the "NOT optional" comments in `freshCtx`;
`.kb/optimize-dead-code-elimination.md`, `.kb/wasm-callable-arity.md`, `.kb/array-literals.md`,
`.kb/concatenate-result-families.md`, `.kb/hash-tables.md`, `.kb/wasm-unboxed-locals.md`). The
latest was `asksStreamDirection` (2026-10-04: top-level `input-stream-p` answered T for an
output string stream on both wasm backends).

Measured 2026-10-04: `Ctx(Builder)` assigns 96 fields from the builder; `freshCtx` still does
not pass these 20:

`bidirectionalStreams`, `duplicatedDefunNames`, `dynSlots`, `hostRefTypeIndex`,
`injectedRuntimeBody`, `injectedRuntimeDefunNames`, `inlinableDefuns`, `litStageBytes`,
`p1Futures`, `parkAllocFuncIndex`, `parkFreeFuncIndex`, `parkStrResultFuncIndex`,
`reactorComponent`, `reentrant`, `reentrantTaskGlobalIndex`, `reentryGuardGlobalIndex`,
`serveInitGlobalIndex`, `usesEval`, `usesProgv`, `warnedClRedefinitions`.

Some may be deliberate (`injectedRuntimeBody` describes the body being compiled; `usesEval`
has a `ctx.topLevel` remark at the main builder). The others look like the same latent bug
(e.g. `dynSlots`/`reentrant` for a top-level special binding under `--reentrant`,
`inlinableDefuns` for top-level call sites).

Plan:

1. Classify each of the 20: for each "inherit" candidate, write a top-level-vs-defun
   differential that shows the divergence, or show that it has none.
2. Replace the field-by-field copy with a builder seeded from the prototype (e.g.
   `Ctx.builder(proto)`), so a new module-wide field is inherited by default. `freshCtx` then
   overrides only the writer, the body stream and the per-body fields, each with a reason.
3. Measure the byte change on the size-report programs, since inheriting e.g.
   `inlinableDefuns` changes top-level emission.
