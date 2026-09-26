# Cache java: overload resolution in both interop bridges

Difficulty: Medium

Measured 2026-09-26 (JDK 25, 1M-iteration loop, JVM output, after warm-up):
Math.max 3518 ns/call, Math.abs 3320, StringBuilder.length 2169,
StringBuilder.append(int) 3617, ArrayList.size 949, Integer.MAX_VALUE 363,
new StringBuilder() 466. The interpreter is the same order (Math.max 3765 ns).
Breakdown (Java microbench): getMethods() ~2.5 us, select() 250 ns (4 candidates)
to 1.4 us (29), Class.forName ~500 ns, a resolved Method.invoke ~46 ns.
A throwaway prototype in JavaBridgeTemplate (selection memo + class cache) gave
Math.max 198, Math.abs 166, length 95, append 151, size 102, field 98, new 168,
with JvmJavaInteropCompilerTest (33 cases) green.

Plan:
- Add the same caches to eval/JavaInterop and JavaBridgeTemplate:
  class name -> Class; (Class, name) -> accessible candidate list;
  (Class, name, argument kind tuple) -> chosen Executable + packed-varargs flag;
  Field / Constructor lookups likewise. ConcurrentHashMap only (the template
  may hold no nested class, so no ClassValue subclass); safe under threads.
- An argument kind is the smallest token that makes the cost function a pure
  function of it: nil, t, integer, float, bignum/ratio, string of length 1,
  other string, BMP character, supplementary character, function value, host
  object (its exact Class). Proper lists, vectors and arrays are element-
  dependent: never memoized, resolved every call as today. A mutable character
  vector is rendered first, then classified.
- On a hit, marshal against the chosen parameter types with the existing
  marshal() and invoke through the existing fail() wrapping, so behavior and
  error messages do not change.
- Tests (both JavaInteropTest and JvmJavaInteropCompilerTest): one call site
  alternating integer/float arguments (Math.max), alternating receiver classes
  (ArrayList vs LinkedList), string length 1 vs longer against a char/String
  overload, a list argument after a scalar one at the same site.
- Record the before/after numbers in .kb/java-interop.md; consider a
  bench-report program for java: calls.
- Rejected: compile-time static binding. Selection depends on the receiver's
  run-time class and on argument values; the compile classpath is the CLI's
  (not the user's, and not reflectable in the native binary); the output would
  depend on the build environment. The remaining gain is tens of ns.
