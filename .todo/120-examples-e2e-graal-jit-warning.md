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

**Root cause found 2026-07-13** (fix candidate 1 executed during the todo-117
pretrained-params work): `java -XX:+PrintCompilation -cp . Prog` names the
failing compile --

```
286  311 %     4       Prog::linalg$colon$colon%la-matmul @ 292 (440 bytes)
  COMPILE SKIPPED: java.util.UnknownFormatConversionException: Conversion = 'l' (not retryable)
```

libgraal passes the method name into a `String.format`-style log path, and the
literal `%` in the mangled `linalg::%la-*` names parses as a format conversion
(`%l` -> UnknownFormatConversionException), killing that one OSR compilation.
So ANY hot `%`-prefixed internal defun can trip it (deep-digits gets there via
its `%la-matmul` training loop); it is timing-dependent in practice (observed
2 of 3 runs on an otherwise idle host). This is a GraalVM bug (JDK 25.0.3
libgraal), but rontolisp can sidestep it entirely:

Candidate fixes (pick one):

1. **Codegen sidestep (preferred)**: map `%` in
   `JvmLispCompiler.mangleMethodName()` like the other forbidden/awkward
   characters (`$pct` alongside `$div`/`$lt`/...); names are internal, so the
   rename is invisible to users -- verify JvmClassShaker roots and the
   `_invoke`/dispatcher paths only ever see mangled names.
2. Harness-side: launch the jvm (run) legs with
   `-Djdk.graal.SystemicCompilationFailureRate=0` (suppresses only the
   detector) or filter host-JVM `[Use -Djdk.graal...]` / `Warning: Systemic`
   lines from captured stdout before comparing.
3. Report upstream (minimal reproducer: any class with a hot method whose name
   contains `%l`, run under libgraal defaults).

Until fixed, ExamplesE2eTest on an Oracle GraalVM host may show this one
failure; CI (Temurin?) is presumably unaffected -- check the workflow JDK
before assuming the suite is broken.
