# The Java front end: provenance, licences and naming

**Invariant: nothing under the Java front end -- its library, its run-time helpers, its tests,
its documentation -- is copied or translated from a JDK; it is written here from the published
specification, and no user surface claims Java compatibility.** Decided 2026-11-17, before the
first line of the front end; not legal advice, the working rule.

Why it binds harder than elsewhere: the library classes are EMBEDDED in every compiled program
(`.class`, `.wasm`, the native executable), so their licence becomes the licence of what a user
ships. This project is Apache-2.0 and its output must stay so.

## The rules

1. **No JDK jar and no JDK class file** is bundled, embedded, extracted or read at build time to
   produce what we ship. The JDK is a tool that runs beside us, never an input to the artefact.
2. **No OpenJDK source is copied, ported or translated** -- into Java, into Common Lisp, into a
   test. OpenJDK is GPLv2 with the Classpath exception; the exception covers linking against
   its classes, not copying them. Reading its source to learn a behaviour and then writing the
   same code is the thing to avoid: learn the behaviour from the API specification, or by running
   a program under a JDK and looking at the output.
3. **No Javadoc text** in our sources, comments or `doc/`. Names, signatures and the specified
   behaviour are what an implementation has to share; the prose is theirs. Constants and
   algorithms the specification itself states (`String.hashCode`'s formula, `Random`'s
   generator) are implemented as specified.
4. **No OpenJDK test** (jtreg and the rest: GPLv2 with no exception) in the repository. The spec
   corpus (`java-spec.yaml`) is written here; a case's expected output comes from running it
   under a JDK, which is use, not copying.
5. **A port is allowed only from an Apache-2.0 implementation**, with its notice kept and the
   origin stated in the file's header and in `NOTICE`. Default is to write it ourselves.
6. **Naming: "an experimental subset of the Java language"**, on every user surface
   (`--source-language` help, `doc/*/java/`, examples, release notes). Never "Java compatible",
   "Java SE", a logo, or a product or module name built on the word: the name is a trademark and
   compatibility is a claim reserved to those who passed the compatibility kit. Class and package
   names inside a user's program (`java.util.ArrayList`) are the API itself and stay as they are.
7. **A third-party parser or library is a separate decision** with its own licence check; the
   front end has its own lexer and parser and needs none.

## What is fine

Implementing the language from its specification; implementing `java.lang` / `java.util` under
the same names and signatures from scratch; matching observable behaviour (a `HashMap`'s
iteration order, `Double.toString`'s text) by our own code; running a JDK in tests and CI as the
oracle.

## When in doubt

A contribution whose author had the JDK's implementation open while writing the same class is
not from scratch: rewrite it from the specification. A reviewer asks where a non-obvious
algorithm came from, and the answer goes in the file's header.
