# k24. Java front end: a program that uses an existing library

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** A library compiled as a program's input is not a port, but nothing of its source
may enter this repository's sources or tests beyond what its licence and that file allow.

Asked by the user (2026-11-19): a Java program should be able to use an existing library,
as long as the library uses only what the front end supports. **Milestone: a Java program
that uses YAVI (`https://github.com/making/yavi`, `am.ik.yavi:yavi`, Apache-2.0) compiles to
wasm and prints what `java` prints.**

## State

- A program is its starting file and the files of ITS source tree it names, lowered whole
  together (`.kb/java-frontend.md`, "A program of several files": `JavaClasses.qualified`
  -> `programClass` -> `JavaFiles` over the read's `SourceLoader`). There is no other root:
  no source path, no jar, no dependency. A class outside the tree and the library
  (`JavaIntrinsics`) is `cannot find symbol`.
- `--java-classpath` / `--java-dep` (`.kb/java-interop.md`, "The program's Java class
  path"; `am.ik.maven`, `.kb/maven-resolver.md`) feed the HOST class loader for interop from
  Lisp and Clojure on the interpreter and JVM. They are bytecode for the host, nothing the
  wasm backends can run.
- The Maven plugin (k09, `rontolisp-maven-plugin/`) compiles a project's Java sources to
  wasm; it does not pass the project's dependencies.

## YAVI, measured 2026-11-19 (shallow clone of `main`, `5949ecd`)

- `src/main/java`: 380 files, 183,636 lines. Most of the bulk is the generated
  `Arguments1..16` / `ReversibleArguments*` validators and builders, which are committed and
  in the sources jar. There is also `src/main/kotlin`, which is out of scope.
- Imports outside `am.ik.yavi`, by count:
  - `java.util` 494, `java.time` 353, `org.jspecify.annotations` 346 (a provided-scope
    dependency that is annotations only), `java.util.function` 279, `java.math` 90,
    `java.util.regex` 44.
  - Fewer than 10 each: `java.util.stream`, `java.net` (`URI`, `URL`, `URLEncoder`),
    `java.text` (`MessageFormat`, `Normalizer`), `java.nio.charset`, `java.io`,
    `java.util.concurrent(.atomic)`, `java.lang.reflect.Array`.
  - Annotation-processor code in `am.ik.yavi.processor` (`javax.lang.model`,
    `javax.annotation.processing`, `javax.tools`), registered through
    `META-INF/services`. A program never names it.
- No Java file names Kotlin. Kotlin, kotlin-reflect and coroutines are optional
  dependencies, used by `src/main/kotlin` only.
- `java.time` beyond `Duration` is not in the front end yet (`.todo/k12`). `ValidatorBuilder`
  has a `_localDate`-style method for every temporal type, so lowering the builder whole
  reaches all of `java.time` even for a program that validates one string.

## Design to settle first (write the decision and its numbers into `.kb/java-frontend.md`)

1. **Source or bytecode.**
   - Recommended starting point: source. Resolve a dependency's `-sources.jar` (or take a
     directory or jar on a source path) and read its classes through the same `JavaFiles` as
     the program's own tree, so that every backend runs the same lowering. That is the
     invariant behind "verified on all four".
   - A bytecode front end (`.class` -> core forms) would also reach jars that ship no sources.
     It would be a second front end with its own semantics, so weigh it and record why it is
     or is not taken.
2. **Surface.**
   - A source path for the CLI and the seam: directories and jars, with a name in javac's
     spirit (`--source-path`?). Check `.kb/source-language.md` for names already taken.
   - Maven coordinates resolved by `am.ik.maven`, with the sources jar beside each jar. Decide
     between reusing `--java-dep` and a separate option by what each means on the JVM backend:
     today a Java program there does not call host classes. Write down which scopes are read
     (compile and provided read, test and optional not), and say what happens to a
     dependency that has no sources jar.
   - The Maven plugin passes the project's compile and provided dependencies the same way.
   - A library's classes are the program's (`Source.library` false): lowered, located with
     the provenance of their jar and path, and checked for access. Their file and line
     numbers must show in a stack trace.
3. **Lower only what is reached.**
   - A library is mostly unused. Lowering a class whole drags in every type its members
     name (the `ValidatorBuilder` -> `java.time` case above).
   - Measure the alternative: reachability over methods before lowering, with members
     resolved lazily. A member that names a type nobody can find, or a construct the subset
     does not have, should then be refused when it is reached, the way the JVM raises a
     linkage error, and not when its class is read.
   - Without this, every library waits on the whole JDK surface it names. Measure compile time
     and module size against 183k lines.
4. **Annotations of an absent type** (jspecify when its jar is not read, `@Generated`):
   decide by what javac does when the annotation type is missing from the class path, and
   pin the choice with a test.
5. **Resources**: `ResourceBundle` / `META-INF` reads a library makes. Say where they come
   from (the jar) or refuse them by name.

## Leads

1. Prove the mechanism with a small library of our own: a fixture jar plus a sources jar
   built in the test, under `src/test/resources` or generated. Run it on all four backends.
   Do not vendor YAVI's source into the repository.
2. For YAVI, add an opt-in measurement that does not run in `./mvnw test`, since it needs the
   network or `~/.m2`: the README's first example (`ValidatorBuilder`, `constraint`,
   `validate`, the violations' messages), compiled with `--target wasm`.
   - List every refusal it hits by name.
   - File each gap as its own Todo (`java.time` joins `.todo/k12`; `MessageFormat`,
     `Normalizer` and the rest are new) and order them by what the milestone needs.
3. Docs: `doc/en/java/` and `doc/ja/java/`, mirrored. Explain how to use a library and what
   decides whether it works ("only what the front end supports", with the refusal a
   program sees).

## Done when

- A Java program that uses a library from a source path or from Maven coordinates runs on
  the interpreter, JVM, wasm and the component, and the fixture test pins it.
- YAVI's README example compiles to wasm and prints what `java` prints. Or, if that slice
  cannot reach it, the remaining gaps are Todos in the queue with the measurement that sized
  them, and this item says so.
