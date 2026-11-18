# Provenance, licences and naming (project-wide; written for the Java front end)

**Invariant: nothing this project ships -- a front end's library, its run-time helpers, its
tests, its documentation -- is copied or translated from a JDK or from any source whose licence
does not allow it; it is written here from the published specification, and no user surface
claims Java compatibility.** Decided 2026-11-17 for the Java front end, before its first line;
**since 2026-11-18 the rules bind the whole project** (user decision, when the Clojure front
end's regex engine was found to be a port of OpenJDK's `Pattern.java`). Not legal advice, the
working rule. The file keeps its name: the todo items and the library's headers cite it.

Why it binds hardest on what is EMBEDDED in a compiled program (`.class`, `.wasm`, the native
executable -- the libraries, the run-time helpers, the tables): its licence becomes the licence
of what a user ships. This project is Apache-2.0 and its output must stay so.

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

## The audit (2026-11-18) and what followed

- **The table**: `.todo/artefacts/j91-license-audit/README.md` -- a read-only audit of the
  whole repository at `b61049e2d`: what was ported from OpenJDK, what was translated from
  EPL sources, what needs a notice, what is clean. The work it found is `.todo/j91`
  (OpenJDK-derived code outside the regex engine), `j92` (Clojure's EPL sources), `j93`
  (`NOTICE` and third-party notices) and `j94`; each records its outcome here when done.
  The ported code stays in git history (user decision, 2026-11-18).
- **The regex engine (j90, 2026-11-18): replaced.** `java.util.regex` of JDK 25 had been
  ported method by method into `clojure.lisp` (2026-10-11, `.todo/f43`, `f49`, `f54`: the
  parser, the matcher's nodes, the look-behind study, `Grapheme.nextBoundary`). All of it
  was deleted and one engine written in its place, `eval/regex.lisp`, shared by the Clojure
  and the Java front ends ([regex-engine.md](regex-engine.md)). How it was learned: the
  syntax, flags, class names and the replacement and split rules from the `Pattern`,
  `Matcher` and `String` API documentation; the grapheme rules from Unicode Standard Annex
  #29; everything the documentation leaves open (refusal indices, which captures survive,
  the look-behind bounds, the matcher's last end) by running patterns under a JDK and
  writing down the answers -- the probes are cases of `ClojureRegexTest`. Nobody working it
  read OpenJDK's regex sources, and the ported code was not used as the model: the new
  engine compiles a tree to closures where the port walked the JDK's node chain. Its
  Unicode tables (`regex-unicode.lisp`) are generated by `RegexUnicodeTablesTest` from a
  running JDK's public `Character` API (use, not copying; the Unicode notice is `j93`'s).
