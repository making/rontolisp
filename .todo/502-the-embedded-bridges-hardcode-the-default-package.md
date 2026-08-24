# The embedded bridges hardcode the default package

Difficulty: Low

Filed 2026-08-24 from the `.todo/501` spike. The prerequisite blocker for that whole item:
until this is fixed there is no such thing as an accelerated rontolisp class inside a Java
package, so nothing downstream can be built or tested.

## The bug

Compile anything to a class that has a package and the accelerated flags die at the first
call:

```
$ java -jar rontolisp-exec.jar kernel.lisp -o com/example/Kern.class --simd
$ java --add-modules jdk.incubator.vector -cp . com.example.Kern
Unhandled condition: RontoLispSimdBridge not in same package as lookup class
Exception in thread "main" java.lang.IllegalArgumentException: RontoLispSimdBridge not in same package as lookup class
```

Same for `--blas` (`RontoLispBlasBridge`), `--gpu` (`RontoLispGpuBridge` and the
`am/ik/gpu/*` classes that travel with it) and `java:` interop
(`RontoLispJavaBridge`). Verified all four.

`MethodHandles.Lookup.defineClass(byte[])` requires the defined class to be in the
lookup class's package. The four builders each rename their template into the DEFAULT
package with a bare `BRIDGE_NAME` constant, which is only the generated class's package
when the generated class has none:

| builder | constant |
|---|---|
| `JvmSimdRuntimeBuilder:38` | `"RontoLispSimdBridge"` |
| `JvmBlasRuntimeBuilder:37` | `"RontoLispBlasBridge"` |
| `JvmGpuRuntimeBuilder:65` | `"RontoLispGpu"` (prefix over `am/ik/gpu/`) |
| `JvmJavaRuntimeBuilder:41` | `"RontoLispJavaBridge"` |

`JvmGpuRuntimeBuilder`'s own javadoc already claims it renames "into the emitted program's
package" -- it has always meant to do this, and the default package made the claim look
true.

## The fix, and why it is small

`JvmJavaRuntimeBuilder.renameClass` is a constant-pool Utf8 rewrite, so a slash-bearing
replacement name is no different from a bare one. Each `build(...)` takes the generated
class's package prefix (`""` or `"com/example/"`) and uses `pkg + BRIDGE_NAME` for both
the rename and the `cp.addClass` reference. `JvmLispCompiler` has the string in
`this.className`; all four call sites are at `JvmLispCompiler:834` / `1252` / `1261` /
`1278`.

Spiked on `JvmSimdRuntimeBuilder` alone -- two edits in the builder, one at the call site
-- and `com.example.Kern` then ran `--simd` correctly. Reverted; nothing kept.

Watch for:

- **`.kb/emitted-output-determinism.md`**: a default-package build must stay
  byte-identical, so the prefix must be `""` there and not `"/"`-normalized into
  something else.
- **`JvmGpuRuntimeBuilder`** is two renames, not one (`TEMPLATE_INTERNAL_NAME` -> bridge,
  then `GPU_INTERNAL_PREFIX` -> `GPU_PREFIX` across the class list that travels), and
  `.kb/gpu.md` names that list -- both need the prefix.
- **`resource-config.json`** lists the template `.class` resources for the native image;
  the rename is a runtime rewrite of already-loaded bytes, so nothing there moves.

## Acceptance

`--simd`, `--blas`, `--gpu` and `java:` each run correctly from a class in a package, with
a test per flag pinning it (the `java:` one belongs next to `JvmJavaInteropCompilerTest`);
a default-package build byte-identical to today's. Then `.kb/java-interop.md`'s "renamed
into the default package" sentence, `.kb/gpu.md`'s travelling-class list and
`.kb/template-class-embedding.md` say the package is the program's.
