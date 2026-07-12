# ExamplesE2eTest: pre-existing GraalVM JIT warning breaks the deep-digits jvm leg

Observed 2026-07-13 on Oracle GraalVM 25.0.3+9.1 (macOS/M4), UNRELATED to any
code change: `ExamplesE2eTest` fails 1 of 172 --

```
[ml/deep-digits.lisp: jvm (run) output did not match the expected value]
```

The numeric output is byte-identical to `.expected/deep-digits.txt`; the diff
is two host-JVM lines injected into stdout by the Graal JIT:

```
[Use -Djdk.graal.LogFile=<path> to redirect Graal log output to a file.]
Warning: Systemic Graal compilation failure detected: 1 of 46 (2%) of
compilations failed during last 0 ms [max rate set by
SystemicCompilationFailureRate is 1%]. ...
```

Facts established while diagnosing:

- Deterministic on this host: `java -cp . DD` (the harness's invocation) prints
  the warning on EVERY run of the deep-digits class; the wall time is normal.
- The generated class is **byte-identical between develop HEAD and the
  declined-shape follow-up working tree** (verified with `cmp` on same-named
  outputs -- note the class embeds the output file name, so compare same-named
  files), so this is not a compiler regression. HEAD fails the same way.
- `java --add-modules jdk.incubator.vector -cp . DD` does NOT print it, and
  neither does `java -XX:-UseJVMCICompiler -cp . DD` -- it is the libgraal JIT
  failing to compile exactly one method of the generated class under default
  flags on this JDK build.

Candidate fixes (pick one):

1. Diagnose the failing method: run with
   `-Djdk.graal.CompilationFailureAction=Print` and see which generated method
   trips libgraal; if it is a rontolisp codegen pattern (e.g. a very large
   method), consider splitting it.
2. Harness-side: launch the jvm (run) legs with
   `-Djdk.graal.SystemicCompilationFailureRate=0` (suppresses only the
   detector) or filter host-JVM `[Use -Djdk.graal...]` / `Warning: Systemic`
   lines from captured stdout before comparing.
3. Report upstream if a minimal reproducer falls out of (1).

Until fixed, ExamplesE2eTest on an Oracle GraalVM host may show this one
failure; CI (Temurin?) is presumably unaffected -- check the workflow JDK
before assuming the suite is broken.
