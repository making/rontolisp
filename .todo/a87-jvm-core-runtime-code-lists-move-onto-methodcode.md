# The JVM core runtime builders' code lists move onto `MethodCode`

Difficulty: High

## The problem

The core runtime still assembles raw `List<Integer>` code patched by position (2026-09-29):
`JvmRuntimeBuilder` (3,728 lines: 1,281 sites, 99 `patchBranch` -- the printer, the dispatch
tables `_invoke_N`/`_lookup` and their segmenting), `JvmNumericRuntimeBuilder` (4,675 lines:
~1,600 `c.add` sites through its own helpers, 194 patches), `JvmComplexRuntimeBuilder` (2,333
lines, the numeric builder's idiom) and `JvmOperandTypeRuntime` (1,086 lines, 20 patches).
(`JvmAsyncRuntimeBuilder` was on an assembler and moved with the others, 2026-09-29.) Until then
the numeric builder's `NumericMethod` carries bytes, so a body built on `MethodCode` reaches it
through `JvmRuntimeBuilder.codeBytes`.

## What is needed

As a86, builder by builder: each helper a `MethodCode`, each position/patch a label, byte-for-byte
comparison of the corpus, mito and jose classes before and after (recipe:
`.kb/jvm-method-size-limits.md`, "How a slice moves"; the raw-list tools are
`.todo/artefacts/a86-jvm-io-and-socket-runtime-code-lists-move-onto-methodcode/`, whose
`raw.py` knows `code.add`/`emitU2`/`patchBranch` -- the numeric builder's own helpers need
teaching). `buildDispatchMethods`
segments by an ESTIMATED size and stays under 32 KB by construction because its raw patch throws;
on `MethodCode` a long branch is written in its `goto_w` form instead, so keep the segment budget
(HugeMethodLimit, `.kb/hot-path-method-size.md`) and drop only the branch-reach reason. Split by
builder if one session is not enough.
