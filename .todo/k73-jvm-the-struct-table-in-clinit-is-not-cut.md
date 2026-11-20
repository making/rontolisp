# k73. JVM: the `#S` struct table in `<clinit>` is not cut past a method

Difficulty: Low

Found by k63 (2026-11-20). `<clinit>`'s work that grows with the program is cut into
`_clinit$..$N` methods past 12,000 B (`JvmClinitSteps`, `.kb/jvm-method-size-limits.md`,
"Bodies bounded by construction"), but `JvmReadRuntimeBuilder.structTableClinit` (one
`Object[]{pkg, member, layout, initTexts}` entry per struct or class type of a program
that `read`s) keeps the table's array on the operand stack across its entries, so it is no
run of stack-neutral steps and stays inline. Not measured: some hundred bytes an entry, so a
program with a few hundred struct types and `read` can pass 65,535 B.

Plan: store the array first (`putstatic`), then make each entry one step
(`getstatic`, index, entry, `aastore`) through a `JvmClinitSteps("_clinit$structs")`.
Measure a generated program of N `defstruct`s and a `read` of `#S(..)` before and after;
below one segment the class must not change apart from the 3 B a step's `getstatic` adds,
or keep the inline form when the run fits one segment.
