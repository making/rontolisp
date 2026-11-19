# k40. Java session: `values()` of an enum an earlier buffer initialized

Difficulty: Low

Found 2026-11-20 with k30, there before it (the same on `278f7e03d`):

```
$ printf 'enum E { A, B }\nSystem.out.println(E.A);\nSystem.out.println(E.values().length);\n' | rontolisp --source-language java
A
Error: Exception java.lang.RuntimeException: The variable j%E.$VALUES is unbound
```

`values()` asked in the buffer that first uses the enum prints 2. Lead:
`JavaClasses.enumMethod` adds the `$VALUES` field where a program first names
`values()` or `valueOf`; a session initializes every class at its first use
(`JavaInitOrder.allLazy`), and the buffer adding the field finds the enum's
initialization done by an earlier one, so nothing sets it. Since k30
`getEnumConstants()` of a class object reads `values()` and has the same end. A
session could give every enum the field where it is declared, or set a field added
later where the buffer adding it starts. A failing step in
`RontoLispCliStreamsTest#aJavaFileRunsCompilesAndIsRefusedWhereItCannot` first.
