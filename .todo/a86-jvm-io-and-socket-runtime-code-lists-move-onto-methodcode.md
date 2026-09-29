# The JVM I/O and socket runtime builders' code lists move onto `MethodCode`

Difficulty: High

## The problem

`JvmIoRuntimeBuilder` (5,344 lines: 2,009 `code.add`/`emitU2` sites, 137 `patchBranch`) and
`JvmSocketRuntimeBuilder` (1,522 lines: 537 sites, 31 patches) build their helpers as raw
`List<Integer>` code with branch offsets patched by POSITION (`int pos = code.size(); ...;
JvmRuntimeBuilder.patchBranch(code, pos, target)`), and a raw-list patch past the 16-bit offset
still throws. Counts 2026-09-29.

## What is needed

Each helper becomes a `MethodCode`; each position/patch pair a label. Unlike the `JvmAsm`
builders (a85) this is not a rename: a position read by `code.size()` can be a branch source,
a target or an exception-table bound, and the handlers (`ClassDefinition.Handler`) take bound
labels (`MethodCode.exceptionCatch`). Recipe and byte-for-byte verification:
`.todo/a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode.md` and
`.kb/jvm-method-size-limits.md`, "Emission on java.lang.classfile". The I/O helpers are reached by
the ci-spec corpus's stream, file and socket cases and by the lack/clack E2Es; compare the corpus
class and a served example's class before and after. Split by builder if one session is not
enough.
