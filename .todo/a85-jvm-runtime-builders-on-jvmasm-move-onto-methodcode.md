# The JVM runtime builders written with `JvmAsm` move onto `MethodCode`

Difficulty: Medium

## The problem

a84 put a `CodeBuilder`-shaped layer over every method body (`am.ik.jvm.MethodCode`, `Ctx.body`)
and moved one slice onto it: `JvmHashRuntimeBuilder` (from `JvmAsm`) and `JvmHashTableCompiler`
(from `ctx.emit`/`patchBranch`). The rest still emit code bytes. This item is every builder that
assembles with `JvmAsm` (2026-09-29 counts of `new JvmAsm()`): `JvmArrayRuntimeBuilder` 38,
`JvmReadRuntimeBuilder` 25, `JvmQuantizedMatrixRuntimeBuilder` 21, `JvmFloatArrayRuntimeBuilder` 16,
`JvmJavaDirectSites` 12, `JvmIntArrayRuntimeBuilder` 9, `JvmExportRuntimeBuilder` 6,
`JvmJavaImplementations` 6, `JvmThrowableRecords` 5, `JvmStringIndexRuntimeBuilder` 4,
`JvmOperandTypeRuntime` 4, `JvmAritySurplusRuntimeBuilder` 2, `JvmFloat16RuntimeBuilder` 2,
`JvmArgvRuntimeBuilder`, `JvmEltCellRuntimeBuilder`, `JvmLengthRuntimeBuilder`,
`JvmNthcdrRuntimeBuilder` 1 each, the blocks `Ctx.emitBlock` splices (`JvmStringCaseFold`,
`JvmSubseqCompiler`, `JvmStringTrimCompiler`, `JvmIntFusionCompiler`), and
`JvmEvalRuntimeBuilder`, whose private `Asm` is `JvmAsm`'s original. Then `JvmAsm` and `Asm` are
deleted.

## The recipe (what the first slice measured)

- `JvmAsm` calls map one to one: `label()`/`bind` -> `newLabel()`/`labelBinding`,
  `branch(Opcode.X, l)` -> `x(l)`, `op(Opcode.X)` -> `x()`, `iconst` -> `loadConstant`,
  `ldcString` -> `ldc`, `anew` -> `new_`, `newarrayInt()` -> `newarray(TypeKind.INT)`; pool
  wrappers become `java.lang.classfile` entries through `ConstantPool.classEntry`/`methodRef`/
  `interfaceMethodRef`/`fieldRef`/`stringEntry` (the same master-pool entries, `.entry()` on a
  wrapper handed in). A regex pass did ~95% of `JvmHashRuntimeBuilder` (1,238 lines); the
  rest was the builder's boundary types. The pass, the byte comparison and the method diff are
  in `.todo/artefacts/a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode/`.
- `MethodCode` emits locals in the explicit-slot form and ints in the shortest, exactly as
  `JvmAsm` did, so a moved sequence measures what it measured and no budget decides
  differently; the writer writes loads/stores shortest either way.
- Verify by bytes, not only by tests: compile the ci-spec corpus, the mito probe
  (`MitoE2eTest`'s program, split into `$Part1`), the jose test suite program and a few
  examples with the jar before and after, and compare every class file. The first slice came
  out identical except the jar's build timestamp that `uiop/os:lisp-version-string` embeds.
- A builder's body is a `MethodCode`; its record carries it (`record HashMethod(name, desc,
  MethodCode code)`) and `code.addTo(definition, access, name, desc)` adds it. The declared
  max_stack/max_locals go.
