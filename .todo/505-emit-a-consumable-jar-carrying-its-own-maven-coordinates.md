# `-o out.jar`: a consumable jar carrying its own Maven coordinates

Difficulty: Medium

Filed 2026-08-24 from the `.todo/501` spike. Independent of `.todo/506` -- the plugin is
for Maven builds, this is for everyone else (Gradle, a plain classpath, `install-file`,
`deploy-file`) and for publishing.

## What the spike proved

Hand-assembled and verified end to end: `jar cfm` the packaged class + a manifest, then
`mvn install:install-file`, then a plain Maven project with an ordinary `<dependency>`
compiled and ran against it. The one non-obvious part is the good part:

**An embedded `META-INF/maven/<groupId>/<artifactId>/pom.xml` + `pom.properties` makes
the coordinates travel inside the jar.** With those present,

```
mvn install:install-file -Dfile=out.jar
```

installs to the right coordinates with **no** `-DgroupId`/`-DartifactId`/`-Dversion`/
`-DpomFile`. Verified: `~/.m2/repository/com/acme/acme-embedded/1.0.0/` got both the jar
and a generated `.pom`. That is "Maven coordinates support" with no new tooling on either
side, and it is the standard layout every Maven-built jar already carries.

## The design

```
rontolisp kernel.lisp -o target/acme-kernels-1.0.0.jar \
    --class-name com.acme.Kernels \
    --maven-coordinates com.acme:acme-kernels:1.0.0 \
    --simd
```

- **`--class-name` is not optional once `-o` is a jar.** Today the class name IS the
  output path (`RontoLispCli:947`: `outputFile.replace(".class","")` handed straight to
  `JvmLispCompiler`), and `-o out.jar` no longer names a class. So the flag is a
  consequence of jar output, not a convenience -- and it should work for `.class` output
  too, where it replaces the path-derived name (and makes CLAUDE.md's "keep the name
  path-free" caveat unnecessary).
- **The jar contents**: the class at its package path, `META-INF/MANIFEST.MF` with
  `Main-Class` when the class HAS a `main` (so `java -jar out.jar` still works for a
  program, and a `--no-main` library jar -- `.todo/503` -- carries no `Main-Class`, which
  is correct for an artifact nobody should `java -jar`), and the two
  `META-INF/maven/...` files when `--maven-coordinates` is given.
- **`--emit-pom` writes the pom NEXT to the jar** as well, mirroring `--emit-wit` next to
  the `.wasm` -- that is the established precedent for "also write the metadata a host
  needs", including its refuse-to-overwrite-a-file-we-did-not-write check
  (`RontoLispCli`'s `--emit-js-glue` guard is the model).
- **Dependencies**: a rontolisp-compiled class has none. It embeds its own bridges
  (`.todo/502`), so the generated pom's `<dependencies>` is genuinely empty -- state that
  in the docs, because it is the property that makes this jar trivial to consume. This
  survived `.todo/504`: a `:float-vector` export's handle class travels INSIDE the
  artifact rather than as a dependency, so **the jar writer must add
  `JvmLispCompiler.runtimeClassFiles()` as entries** (the `.class` path writes them next
  to the output class today, `RontoLispCli`) -- forgetting them is a `NoClassDefFoundError`
  in the consumer, not a compile error here.
- Do NOT run `install`/`deploy` from the CLI. Writing the artifact is ours; putting it in
  a repository is Maven's, and `install-file`/`deploy-file` already do it.

## Notes

- A `--simd` jar's consumer needs `--add-modules jdk.incubator.vector` -- and today gets a
  `NoClassDefFoundError` without it rather than a scalar fallback. That is `.todo/507`,
  and it is a jar's problem more than a `java Prog`'s, because the consumer did not choose
  the flag. Consider a `Multi-Release`/`Add-Opens`-style manifest hint or at least an
  entry in the generated pom's `<description>`.
- `-o com/acme/Kernels.class` does not create the directory today
  (`NoSuchFileException`); jar output must not repeat that.
- Sources/javadoc jars: out of scope. A `-sources` jar of the `.lisp` input is a cheap
  and genuinely nice follow-up once the rest works.

## Acceptance

A CLI test compiling to a jar and asserting the entry list, the manifest and the embedded
pom; an E2E that installs it into a scratch local repository and compiles a consumer
against it (the `ExamplesE2eTest` harness pattern, gated behind a system property like the
other heavy suites). Docs: the `--help` block, and a `doc/{en,ja}/guides/` section of the
`.todo/503` page rather than a page of its own.
