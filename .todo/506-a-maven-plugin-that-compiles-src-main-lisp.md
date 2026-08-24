# `rontolisp-maven-plugin`: compile `src/main/lisp` into `target/classes`

Difficulty: Medium

Filed 2026-08-24 from the `.todo/501` spike. The most Maven-native of the three packaging
answers and the one a Java developer will actually reach for: no jar to assemble, no
coordinates to declare, no `install-file` -- the Lisp is just another source set, and
Maven's own `jar`/`install`/`deploy` do the rest.

## The shape

```xml
<plugin>
  <groupId>am.ik.rontolisp</groupId>
  <artifactId>rontolisp-maven-plugin</artifactId>
  <version>...</version>
  <executions>
    <execution>
      <goals><goal>compile</goal></goals>
      <configuration>
        <sourceDirectory>src/main/lisp</sourceDirectory>
        <simd>true</simd>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- Goal `compile`, bound to `compile` by default (`test-compile` for a `testCompile`
  twin), writing into `${project.build.outputDirectory}` so `maven-jar-plugin` picks the
  classes up with no further configuration.
- The class name per file comes from the file's path under the source directory
  (`src/main/lisp/com/acme/Kernels.lisp` -> `com.acme.Kernels`), which is the convention
  every JVM-language plugin uses and needs no per-file declaration.
- One flag per CLI flag that reaches the JVM backend (`simd`, `blas`, `gpu`, `parallel`,
  `optimize`, `dynamic`, `systemPath`, `dist`), named the same.
- Incremental: skip a file whose `.class` is newer, so a mixed Java/Lisp project does not
  recompile the world. `maven-compiler-plugin`'s staleness check is the model.
- Report a compile error as a `MojoFailureException` carrying the rontolisp diagnostic
  verbatim (it already has `file:line:col`, so an IDE can jump to it).

## Structure

A separate Maven module, NOT in the root reactor -- `docs-tool/` is the precedent
(`am.ik.rontolisp:rontolisp-docgen`, its own `pom.xml`, built with
`./mvnw -f docs-tool/pom.xml`). That keeps the root project's "no external dependencies in
the core libraries" rule intact while the plugin depends on `maven-plugin-api`,
`maven-plugin-annotations` and `maven-core` as it must. It depends on the rontolisp jar
by coordinates, so it needs a released (or locally installed) rontolisp -- worth deciding
early whether the plugin's release cycle rides along with `How-To-Release.md` or is its
own.

Call the compiler through `JvmLispCompiler` and the compile-path splice chain **in
process**, not by shelling out to the CLI: the splice chain, `LoadInliner`,
`TlsPemInliner` and the library pre-passes are all `cli`-side today, so this will surface
whichever of them a library build needs and does not have. Expect to lift a piece of
`RontoLispCli`'s compile path into a reusable seam -- that refactor is most of the work in
this item, and it is worth doing anyway (the same seam is what `.todo/505` needs).

## Notes

- `--gpu` and `--blas` reach a native library at RUN time, not build time, so the plugin
  needs nothing special for them; `--simd` needs `--add-modules jdk.incubator.vector` on
  the CONSUMER, which is `.todo/507`.
- The plugin should not need `.todo/505`: Maven jars `target/classes` itself and the
  project's own pom is the coordinates. The two items are alternatives for different
  consumers, not a stack.
- A `run` goal (interpret a `.lisp` during a build, `exec-maven-plugin`-style) and Gradle
  support are follow-ups, not this.

## Acceptance

The module builds, and an integration test (`maven-invoker-plugin`, or a scratch project
driven from a JUnit test the way `ExamplesE2eTest` drives the binary) runs a real project
whose `src/main/lisp` kernel is called from `src/main/java` and asserts the answer.
`CLAUDE.md` gains the module to its build-command list the way `docs-tool/` is listed.
