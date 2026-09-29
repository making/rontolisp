# `MethodCode` stores instructions, and the code bytes go

Difficulty: High

## The problem

Once a86-a90 have moved every emitter onto `am.ik.jvm.MethodCode`, the code bytes survive only
inside the layer: `MethodCode` still encodes each instruction into a `List<Integer>`, the
operand-stack model (`OperandStack.feed`) and the class writer (`CodeReplay`) decode them again,
`ClassDefinition.Method` carries the list, the long branches ride as placeholder offsets, and
the pool operands are master-pool indexes kept whole in a u2's high part
(`.kb/jvm-method-size-limits.md`, "Emission on java.lang.classfile").

## What is needed

- `MethodCode` records instructions (opcode, operand, entry or label); `size()` is the written
  size (shortest local and int forms, `ldc` by the master index -- the writer re-decides it --
  and the long form of a branch the layer knows to be far), so the budgets read the truth.
  `append` (the dispatch tables' spliced case bodies) copies a block's records, its labels
  rebased. The dispatch partition then changes by itself: a case's `aload 1`..`aload 3` count
  one byte (a87 measured a spread case's `aload 1` at two, `_invoke_v` 72 -> 75 segments on the
  mito probe).
- `CodeReplay` plays records: its byte decoder, `length` and the index readers go; its
  relaxation fixpoint (`Layout`) runs over record sizes, and the writer stays under
  `FAIL_ON_SHORT_JUMPS`.
- The operand-stack model is fed typed instructions (`OperandStack`'s byte state machine and its
  `wide` handling go); `Ctx.stack`'s users keep their queries.
- `ClassDefinition.Method` holds the body; `JvmClassSplitter.Scan` reads operands as entries.
- Deleted: `am.ik.jvm.Opcode`, `JvmRuntimeBuilder.codeBytes`, the `ConstantPool` wrapper types and
  facade methods the emitters no longer call (the core runtime builders still take the wrappers
  as parameters and call `.entry()` at each typed call: a86's `pool.py` moves them, the minting
  order kept), the pool-index-origin instrument (no index sink
  is left to cut), and the byte fixtures in `src/test/java/am/ik/jvm` (`CodeReplayTest`,
  `LineNumberTableTest`, `JvmClassSplitterTest` build bodies from bytes) -- rewritten on
  `MethodCode`.
- Measure against a84's numbers: compile time of the corpus and the mito probe, output size.
