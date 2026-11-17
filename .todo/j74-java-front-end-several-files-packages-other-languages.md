# j74. Java front end: several files, packages, the other languages, a session

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".

**First target (j68): the first bullet only** -- packages and imports are the language.
After, in this order: the session and playground (wanted: the first thing taken up once
the target is met), the calls across languages, `rontolisp test`, the formatter and the
Maven plugin.

After j68, which reads one file.

- **A program of several files**: `package` and `import` (single, on demand, static)
  and a module import (`import module java.base`: every
  class of the library we have) resolved through the `SourceLoader` the read already carries (`.kb/source-language.md`,
  "The files a source names travel with the read too"), the directory layout a package
  names, a class found beside the entry file without being named on the command line.
  The lowering needs the whole program's class table for its direct calls and dispatch
  sets (j68), so the unit of lowering is the program, not the file: say how a `(load
  "x.java")` from Common Lisp and a per-file read agree with that.
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

## State after j68 (2026-11-17, `.kb/java-frontend.md`)

One file. `package` is part of every Lisp name of its classes; single-type, on-demand and
`import module java.base` imports resolve against the file and the library only; a static
import is refused. `SourceSession` refuses `JAVA` (no REPL, no playground pick); a ` ```java `
doc fence is checked by `DocExamplesTest` and rendered as static code.
`SourceLanguage.isSourceFile` answers true for `.java`; its readers are not checked yet.
