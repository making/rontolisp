# The rest of the JVM expression compilers move onto `ctx.body`

Difficulty: Medium

## The problem

After a89, 121 compiler files still emit bytes: 1,605 lines with `ctx.emit`/`ctx.emitU2`
(2026-09-29), none with more than 58 -- `JvmFunctionFormCompiler`, `JvmSortCompiler`,
`JvmSetqCompiler`, `JvmLambdaCompiler`, `JvmExprCompiler` (50), `JvmReduceCompiler`,
`JvmStringTrimCompiler`, `JvmLetCompiler`, `JvmApplyCompiler`, `JvmFreshLineCompiler`,
`JvmLinalgKernelCompiler`, `JvmPhysicalArgs`, `JvmArrayCompiler`, `JvmBodyOutliner`, the
remaining predicates and the many two-to-twenty-site operators (a89 already moved `atom`,
`consp`, `listp`, `numberp`, `arrayp`). `JvmEmitHelper.patchBranch`, `Ctx.emit`,
`Ctx.emitU2` and `Ctx.emitBlock` have no caller left when this item ends.

## What is needed

- The a89 recipe (`.kb/jvm-method-size-limits.md`, "How a slice moves"): `ctxmig.py` over a
  file does the straight-line code and the common branch shapes, `listlabel.py` the named
  position lists; javac (`jc.sh`) then `fix.py` for the `.entry()` flavours; the rest by
  hand. Verified byte for byte (`runchunks.sh` over the 4,857 extracted programs, `cmpcli.sh`).
- `UnwindScope.holes` becomes pairs of labels (recorded by `JvmReturnCompiler` /
  `JvmGoCompiler`), so the protected-range sweeps (`JvmUnwindProtectCompiler`,
  `JvmHandlerCaseCompiler.addExceptionEntries`, which still reads `ctx.body.size()`
  positions) add their entries with `exceptionCatch`.
- When no compiler reads `ctx.code` any more, the chunker (`chunkCtx.code.size()`, Pass 2b)
  and `JvmBodyOutliner`'s budget read `body.size()`, and `Ctx.emit`/`emitU2`/`emitBlock`/
  `deferredBranches` and `JvmEmitHelper.patchBranch` are deleted. `JvmEmitHelper.branch(ctx,
  int, label)` (a branch whose `am.ik.jvm.Opcode` is chosen at run time) stays until a91
  retires the int opcodes.
