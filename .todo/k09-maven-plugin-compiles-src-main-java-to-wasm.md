# k09. Maven plugin: compile `src/main/java` to a `.wasm`

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** User surfaces say "an experimental subset of the Java language".

A Maven project's `src/main/java` (a `main` class and the classes it reaches) builds to a
`.wasm` with `mvn package`, through the Java front end. Today the only way is the CLI
(`examples/java/README.md`).

## Decided (user, 2026-11-19)

- **Extend the existing `rontolisp-maven-plugin`** (`.kb/jvm-export.md`, "`rontolisp-maven-plugin`"),
  not a new plugin. It is hardly used, so its current goals, parameters and defaults may
  change where a shared design needs them; record each change in `.kb/jvm-export.md` and the
  plugin's doc (`doc/*/guides/jvm-library.md`).

## Leads

- Compile IN PROCESS as the existing goals do: `cli/CompileFrontend` + the wasm compile the
  CLI's `-o x.wasm` uses (one code path with the CLI, as
  `JvmSourceCompilerTest#theEmbeddedCompileIsByteIdenticalToTheCommandLines` pins for the
  JVM). The front end finds a program's other classes from the starting file's source root
  (`.kb/java-frontend.md`, "A program of several files"), which is `src/main/java`.
- Parameters to decide: the main class (or file), the output path (default under
  `target/`), the CLI's wasm flags by their CLI names (`--component`, `--optimize`,
  `--host-fetch` ...; `--native` if the runner is reachable from a plugin), the phase (`package`
  is the natural one: `src/main/java` still compiles with javac, so the same sources keep
  running on a JDK and their tests keep running in surefire).
- Staleness: the existing goals keep a status file (`target/rontolisp/compile-status.txt`),
  all-or-nothing; the same rule fits a program.
- A dependency jar's classes cannot reach the front end (it reads source); a class the program
  names from a dependency is refused by name, as the CLI refuses it. Say so in the doc.
- Front-end refusals and warnings reach Maven as a `MojoFailureException` with file and
  position (`cli/CompileDiagnostics`, `warningsAsErrors`).
- An HTTP server program (`.todo/k04`) built with `--component` should be runnable with
  `wasmtime serve` straight from `target/`.

## Done when

- `MavenBuildE2eTest` builds a sample project whose `src/main/java` (two packages, a thread
  or a record, nothing exotic) becomes a `.wasm` (Preview 1 and `--component`) whose output
  matches `java` on the same sources, and pins a refusal's message through Maven.
- `doc/en` + `doc/ja` describe the goal; `.kb/jvm-export.md` (or a new `.kb` note, indexed in
  `.kb/README.md`) records the design.
