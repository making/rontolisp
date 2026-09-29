# The large JVM expression compilers move onto `ctx.body`

Difficulty: Medium

## The problem

The expression compilers emit into a compile context with `ctx.emit`/`ctx.emitU2` and patch
branches by position (`int pos = ctx.code.size(); ...; JvmEmitHelper.patchBranch(ctx, pos,
target)`). The largest (2026-09-29 `ctx.emit`/`emitU2` sites): `JvmIntFusionCompiler` 333,
`JvmSymbolApiCompiler` 317, `JvmHandlerCaseCompiler` 220, `JvmTypedLoopCompiler` 185,
`JvmObjCompiler` 176, `JvmSimdCompiler` 157, `JvmEmitHelper` 121, `JvmComplexCompiler` 99,
`JvmBFloat16Compiler` 93, `JvmMapcanCompiler` 92, `JvmNlxCompiler` 92, `JvmQuoteCompiler` 83,
`JvmCharCompiler` 82, `JvmIntConvCompiler` 76, `JvmRemfTailCompiler` 75,
`JvmSimpleArrayPCompiler` 72, `JvmGeomKernelCompiler` 67, `JvmMapcarCompiler` 66,
`JvmStringpCompiler` 63 -- about 2,500 of the 3,778 sites in 139 compiler files.

## What is needed

`JvmHashTableCompiler` is the worked example (a84): `ctx.body` writes into the same list and
feeds the same operand-stack model, so a file moves on its own and a body mixes the two. A
label replaces each position/patch pair; `exceptionCatch` takes bound labels; `enterHandler`,
`joinShape` and the spills stay calls on `ctx.stack` until a91. Verify byte for byte (corpus,
mito, jose; recipe in `.kb/jvm-method-size-limits.md`, "How a slice moves"): the
layer encodes a local exactly as `ctx.emit` does, so every budget measures what it measured.
Watch for the bug class the first slice found twice: a raw `iinc` (and anything else
`Ctx.emit`'s `wide` rewrite does not see) wrote its slot in one byte, which past 255 names
another local (`JvmLispCompilerTest#aMaphashWhoseCounterLandsPastSlot255IncrementsItsOwnLocal`).
