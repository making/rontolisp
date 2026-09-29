# The rest of the JVM expression compilers move onto `ctx.body`

Difficulty: Medium

## The problem

After a89, about 120 compiler files with fewer than 60 `ctx.emit`/`ctx.emitU2` sites each
(~1,300 sites, 2026-09-29) still emit bytes: `JvmFunctionFormCompiler`, `JvmSortCompiler`,
`JvmSetqCompiler`, `JvmLambdaCompiler`, `JvmExprCompiler` (50), `JvmReduceCompiler`,
`JvmLetCompiler`, `JvmApplyCompiler`, `JvmFreshLineCompiler`, `JvmLinalgKernelCompiler`,
`JvmPhysicalArgs`, `JvmArrayCompiler`, `JvmBodyOutliner`, the predicates and the many
two-to-twenty-site operators. `JvmEmitHelper.patchBranch`, `Ctx.emit`, `Ctx.emitU2` and
`Ctx.emitBlock` have no caller left when this item ends.

## What is needed

The a89 recipe, file by file, verified byte for byte. When no compiler reads `ctx.code` any more,
the chunker (`chunkCtx.code.size()`, Pass 2b) and `JvmBodyOutliner`'s budget read `body.size()`,
and `Ctx.emit`/`emitU2`/`emitBlock`/`deferredBranches` and `JvmEmitHelper.patchBranch` are
deleted.
