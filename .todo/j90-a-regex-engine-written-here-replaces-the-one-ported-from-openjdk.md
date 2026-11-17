# j90. A regex engine written here replaces the one ported from OpenJDK

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first.** Its rules now bind the whole project, not
only the Java front end (user decision 2026-11-18: the Clojure engine may not stay as it is).

## The defect

The Clojure front end's regex engine in `src/main/resources/am/ik/rontolisp/eval/clojure.lisp`
is `java.util.regex` of JDK 25 ported method by method from OpenJDK's `Pattern.java`
(`.kb/clojure-frontend.md`, "Regex": "Ported 2026-10-11 (`.todo/f43`)"; a search constant
"read off `Pattern.java` of JDK 25"). OpenJDK is GPLv2 with the Classpath exception; the
exception covers linking, not porting. The engine is embedded in every compiled Clojure
program, so its licence reaches what a user ships. This project is Apache-2.0.

## The work

- **One engine, written here from the specification** -- the `java.util.regex.Pattern` API
  description of the syntax (constructs, flags, quantifier kinds, the error conditions it
  names) and observed behaviour, with a JDK as the oracle of a differential test (running
  it is use, not copying). Nobody working this item reads OpenJDK's regex sources, and the
  ported code is not consulted as a reference for the structure of the new one: it is
  deleted, not refactored.
- **Shared by the Clojure and the Java front ends.** This takes over j87's "Regular
  expressions" bullet (`matches`, `replaceAll`, `replaceFirst`, `Pattern`, `Matcher`,
  `split` moving onto it). Decide where it lives so both reach it (Java source of the Java
  library lowered for both, or Lisp in a shared run-time library) and measure the choice on
  all four backends against the current engine's numbers in `.kb/clojure-frontend.md`.
- **Keep what the Clojure corpus pins**: `ClojureRegexTest`, the `clojure-spec.yaml` regex
  cases, the random differential against `java.util.regex` (34,000 patterns, `.kb`). Where
  the new engine differs from the JDK, that is a deviation to fix or to document, not a
  case to delete.
- **Audit for other ports** across the repository: grep `.kb/`, `src/`, `doc/` and the git log
  for "ported", "read off", "transcribed", "`*.java` of JDK", OpenJDK file names, and the
  `.todo/f43` history. Each hit is either learned from the specification / an oracle run
  (write down how) or replaced the same way. Record the result in
  `.kb/java-frontend-provenance.md` (rename or widen it if it now governs the project).
- **Records**: rewrite the "Regex" section of `.kb/clojure-frontend.md` (no "ported" claim
  survives that is not true), and the user docs in `doc/en/**` + `doc/ja/**` where they
  describe the engine.

## Out of scope, for the user

The ported code remains in git history (`.todo/f43`'s commits). Whether to rewrite published
history is the user's decision; this item does not touch it.
