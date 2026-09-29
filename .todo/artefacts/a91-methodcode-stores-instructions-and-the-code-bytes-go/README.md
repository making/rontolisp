# `MethodCode` stores instruction records: the tools

The recipe is `.kb/jvm-method-size-limits.md`, "The records". Run from the repository with
`PYTHONDONTWRITEBYTECODE=1`; the jars and the byte comparison are a87's `mkjar.sh` and a85's
`runchunks.sh` / `cmpcli.sh` / `Cmp.java` (with `ROOT` and `WORK` set), plus the gate programs of
a86 and a88.

Converting:

- `opcode_enum.py FILE...` -- an opcode passed around as an `int` of `am.ik.jvm.Opcode` becomes
  the `java.lang.classfile.Opcode` it names (the `int` locals, parameters and returns by name).
- `addto.py [--list] FILE...` -- `code.addTo(definition, access, name, desc)` on a `MethodCode`
  becomes `definition.addMethod(access, name, desc, code)`; a compile context's own `addTo`
  (the receivers in its `CTX` set) stays.

Verifying:

- `legacy.py apply|revert` -- the temporary patch the records compared bytes under (the measure
  as the bytes measured it): byte-identical with it, then the measure alone without it.
- `measure.sh JAR LABEL [RUNS]` -- compile time and sizes of the corpus and the mito probe.
- `ClassStats.java`, `Families.java`, `FrameBytes.java` -- a class's parts (methods, code,
  frames, lines, pool), its method families side by side (`_invoke_v$k` segments, `_top$k`
  chunks), and what its frames take.

Expected differences with the patch: none (a failure message may differ in a class loader's hash).
Without it: the budgets' cuts ("The records").
