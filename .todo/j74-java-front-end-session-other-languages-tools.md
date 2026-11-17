# j74. Java front end: a session, the other languages, the tools

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".

**Done 2026-11-17: a program of several files** (`.kb/java-frontend.md`, "A program of several
files"): packages, single, on-demand, static and module imports, the source tree found as a
source launcher finds it, access across packages, `(load "x.java")` as the read of the program
that file starts. **Next target: the session and the playground**, then the calls across
languages, `rontolisp test`, the formatter and the Maven plugin, then annotations.

- **Across languages** (`.kb/starlark-frontend.md`, "Across languages", for the
  precedent): a Common Lisp function called from Java and a Java static method called
  from Common Lisp, under one stated name mapping (a method's Lisp name carries its
  signature); what a Java object is to a Lisp caller (a structure) and a Lisp list to a
  Java one.
- **A session** (`SourceSession`): a buffer is a statement, an expression or a
  declaration, as a snippet shell reads them, each keeping the earlier ones' names; the
  REPL's `java> ` and the browser playground's pick (`eval/PlaygroundRepl`, the doc
  site's Run cells for `doc/*/java/`).
  What a session changes in the lowering: a file's program is closed, so a call with one
  implementation is direct and a dispatch set is fixed (j68); a later buffer may add a
  subclass, an overload or a new body for a method an earlier buffer already called. So a
  session lowers every call through a table a later buffer can replace -- or lowers the
  affected earlier declarations again -- and the choice is measured on the interpreter,
  the only backend a session runs on. An expression buffer echoes its value under a
  generated name with its static type; a statement needs no `;`; a top-level variable is
  a global the later buffers see with its declared type; a redeclaration replaces.
- `rontolisp test`, the formatter and the Maven plugin (`rontolisp-maven-plugin`, where
  `src/main/java` already means the host compiler: choose a directory that does not
  collide) decide what a `.java` file is to them; `SourceLanguage.isSourceFile` widens
  with j68 and every site reading it is checked here.
- Annotations are parsed and dropped; say so, and refuse the ones whose absence changes a
  program's meaning.

## State (2026-11-17)

`SourceSession` refuses `JAVA` (no REPL, no playground pick); a ` ```java ` doc fence is checked
by `DocExamplesTest` and rendered as static code. `SourceLanguage.isSourceFile` answers true
for `.java`; its readers are not checked yet. A session's buffers read no other file of a
source tree: decide whether a session has one (the REPL's working directory) or none.
