# e35. A Java library is not reachable at run time; no Maven coordinate for one

Difficulty: Medium

`--java-classpath` only feeds the JVM COMPILER's site resolution (`JavaResolutionOptions`,
`JvmClassFileLookup`, `.kb/java-interop.md`). The interpreter resolves through
`ReflectiveJavaClasses`, `Class.forName(name, false, ReflectiveJavaClasses.class.getClassLoader())`
-- the CLI's own loader -- so a jar a program needs cannot be added at all. A compiled
`-o prog.jar` records no `Class-Path` and bundles nothing, and `--emit-pom` writes
`<dependencies/>` empty. A CL program using a Java library must be launched by hand with
`java -cp`.

A `deps.edn` whose Clojure library depends on a Java library (`e39`) hits the same wall on the
interpreter and the JVM backend; wasm stays a call-time refusal (`java:` is refused there).

## Gaps

1. Interpreter: a class loader over the declared jars for `ReflectiveJavaClasses` and every
   other run-time `java:` lookup (failing test first: a class only in a `--java-classpath`
   jar resolves at compile time but not in `rontolisp run`).
2. Compiled jar: the declared jars reach `java -jar` (manifest `Class-Path` vs bundling;
   decide, with `--native` / native-image in view) and `--emit-pom` lists them.
3. Maven coordinates (proposed: `--java-dep group:artifact:version`, repeatable), feeding 1
   and 2. `am.ik.maven` collects the graph with every version seen
   (`MavenResolver.collect`, `.kb/maven-resolver.md`); the selection is this item's: Maven's
   nearest-wins with its scope derivation and optional handling (Resolver's
   `ConflictResolver`), checked against Maven with `MavenOracle`'s `resolve` mode. Settle the
   surface in step 1.

## Plan

1. Read `.kb/java-interop.md`, `.kb/jvm-export.md`, `.kb/running-backends.md`,
   `.kb/maven-resolver.md`.
2. Gap 1 (red test, then fix), gap 2, gap 3.
3. `.kb/java-interop.md`; user docs in `doc/en` + `doc/ja` (`.kb/documentation-site.md`).
