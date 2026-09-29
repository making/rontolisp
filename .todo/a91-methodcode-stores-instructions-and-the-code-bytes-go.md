# `MethodCode` stores instructions, and the code bytes go

Difficulty: High

## The problem

Once a85-a90 have moved every emitter onto `am.ik.jvm.MethodCode`, the code bytes survive only
inside the layer: `MethodCode` still encodes each instruction into a `List<Integer>`, the
operand-stack model (`OperandStack.feed`) and the class writer (`CodeReplay`) decode them again,
`ClassDefinition.Method` carries the list, the long branches ride as placeholder offsets, and
the pool operands are master-pool indexes kept whole in a u2's high part
(`.kb/jvm-method-size-limits.md`, "Emission on java.lang.classfile").

## What is needed

- `MethodCode` records instructions (opcode, operand, entry or label); `size()` is the written
  size (shortest local and int forms, `ldc` by the master index -- the writer re-decides it --
  and the long form of a branch the layer knows to be far), so the budgets read the truth.
- `CodeReplay` plays records: its byte decoder, `length` and the index readers go; its
  relaxation fixpoint (`Layout`) runs over record sizes, and the writer stays under
  `FAIL_ON_SHORT_JUMPS`.
- The operand-stack model is fed typed instructions (`OperandStack`'s byte state machine and its
  `wide` handling go); `Ctx.stack`'s users keep their queries.
- `ClassDefinition.Method` holds the body; `JvmClassSplitter.Scan` reads operands as entries.
- Deleted: `am.ik.jvm.Opcode`, `JvmAsm` (if a85 left it), the `ConstantPool` wrapper types and
  facade methods the emitters no longer call, the pool-index-origin instrument (no index sink
  is left to cut), and the byte fixtures in `src/test/java/am/ik/jvm` (`CodeReplayTest`,
  `LineNumberTableTest`, `JvmClassSplitterTest` build bodies from bytes) -- rewritten on
  `MethodCode`.
- Measure against a84's numbers: compile time of the corpus and the mito probe, output size.
