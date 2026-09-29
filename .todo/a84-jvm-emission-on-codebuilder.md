# JVM emission on `java.lang.classfile.CodeBuilder`

Difficulty: High

## The problem

`codegen.jvm` (~135k lines, 213 files, 2026-09-29) writes bytecode as raw bytes: `ctx.emit(opcode)`
~3,300 sites, `ctx.emitU2`/`emitU` ~980, `patchBranch` ~816, over `List<Integer>` code lists and
the hand-written `am.ik.jvm` assembler (`ConstantPool`, `ByteCodeWriter`, `ClassDefinition`,
`BranchRelaxer`, `OperandStack`, the `wide` rewrite in `Ctx.emit`). `CodeBuilder` gives labels with
automatic `goto_w` relaxation (`ShortJumpsOption.FIX_SHORT_JUMPS`), automatic `wide`, constant
pool, max_stack/max_locals, while still allowing an exact opcode (`LoadInstruction.of(Opcode, n)`
etc.).

Two designs depend on byte positions during emission and block a direct port:

1. **Size budgets.** Chunking decides on the current code size (`chunkCtx.code.size() >=
   chunkCodeBudget` in `JvmLispCompiler`, `.kb/jvm-method-size-limits.md`); `CodeBuilder` exposes
   no current bci.
2. **The lossless over-limit pool.** `ConstantPool.unbounded()` grows past 65534 entries and
   `JvmClassSplitter` re-points `Methodref`s to `$PartN` at write time. A Class-File API pool is
   one per class, u2-indexed.

## What is needed

- Depends on the stack-map and reader items; do not start before both are done.
- First, a design for the two blockers above, measured on the mito probe (`MitoE2eTest`, 83,456
  entries unshaken on 2026-09-24): e.g. build methods first and measure the finished
  `CodeAttribute` length, and choose the owning class before emission from an estimate, or keep
  a symbolic method IR and assign owners at write time. Write the result into
  `.kb/jvm-method-size-limits.md`. If no design keeps the split and the budgets without a
  blast radius larger than the code removed, record that and cancel.
- Then put a thin `Ctx` layer over `CodeBuilder` and migrate one slice end to end (e.g. one
  `Jvm*RuntimeBuilder` and the `Jvm*Compiler`s it serves), keeping the JVM ci-spec corpus green.
  Measure size and compile time against the old path.
- This item ends there: file the remaining slices as their own items from what the first slice
  measured (runtime builders, expression compilers, the chunker, the splitter, then deleting the
  hand assembler and its tests under `src/test/java/am/ik/jvm`).
